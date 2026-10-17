package com.ladygo.driver.realtime

import com.ladygo.driver.BuildConfig
import com.ladygo.driver.api.generated.apis.SystemControllerApi
import com.ladygo.driver.api.generated.models.BookingDetailResponse
import com.ladygo.driver.api.generated.models.OfferResponse
import com.ladygo.driver.auth.SessionManager
import com.ladygo.driver.auth.TokenRefresher
import com.ladygo.driver.auth.TokenStore
import com.ladygo.driver.di.AppScope
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.hildan.krossbow.stomp.StompSession
import org.hildan.krossbow.stomp.subscribeText
import org.hildan.krossbow.websocket.WebSocketConnectionException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the one driver-side STOMP connection (design doc §2.2): connects only while told to (the
 * driver is ONLINE - see [com.ladygo.driver.ui.HomeViewModel]), authenticates the WebSocket handshake
 * itself (never the STOMP CONNECT frame - the backend's `AuthHandshakeInterceptor` only ever reads
 * the handshake's own `Authorization` header), refreshes the access token proactively before every
 * (re)connect attempt rather than waiting for a rejection, reconnects with exponential backoff and
 * jitter, and subscribes to `/user/queue/offers` and `/user/queue/booking` - the only two real
 * server-to-client destinations this backend has (confirmed against `MatchingService`'s only two
 * `pushToUser` call sites; there is no client-to-server STOMP destination this app needs at all).
 *
 * Deliberately does not special-case the server's forced close on token expiry (the backend's
 * `SessionExpirySweepJob` closing with `POLICY_VIOLATION`/"token expired"): whatever closed the
 * connection, the *next* connect attempt always re-checks the token's own expiry first and refreshes
 * if it's expired or about to be - so a forced-expiry close is handled by the same path as any other
 * disconnect, not a special close-code parse. A 401 *at handshake time* specifically still gets one
 * immediate refresh-then-retry within the same attempt (design doc: "a 401 at handshake means
 * refresh-then-reconnect, not a bare retry"), rather than waiting out a full backoff delay first.
 *
 * No duplicate-connection risk from [connect] being called twice while already connected (it's a
 * no-op if a connection attempt is already active), and [disconnect] always closes the live socket
 * before returning. The one case this can't prevent is a killed process's socket, which the OS drops
 * without a clean close frame - the server's own TCP-level detection reclaims that connection slot on
 * its own; nothing this class does differently would change that, and
 * [com.ladygo.driver.ui.HomeViewModel] already starts every fresh process at `OFFLINE` (never
 * auto-reconnecting on its own), so a new connection is never opened until well after the old
 * process - and its socket - are already gone.
 */
@Singleton
class StompConnectionManager
    @Inject
    constructor(
        private val stompSessionFactory: StompSessionFactory,
        private val tokenStore: TokenStore,
        private val tokenRefresher: TokenRefresher,
        sessionManager: SessionManager,
        private val systemApi: SystemControllerApi,
        private val realtimeEvents: RealtimeEvents,
        private val moshi: Moshi,
        @AppScope private val scope: CoroutineScope,
    ) {
        private val backoff = StompBackoff()
        private var connectionJob: Job? = null

        // connect()/disconnect() are called from more than one context - HomeViewModel's
        // viewModelScope (Dispatchers.Main.immediate) and this class's own isLoggedIn observer below
        // (dispatched on [scope], a background dispatcher) - without this, a disconnect() racing a
        // connect() could leave [connectionJob] pointing at a job that was never actually cancelled
        // (fresh-context review finding).
        private val connectionLock = Mutex()

        init {
            // Logout must close the socket immediately, from wherever in the app it happens - same
            // "one observer, works no matter the screen" reasoning as LadyGoNavHost's own logout
            // handling for navigation.
            scope.launch {
                sessionManager.isLoggedIn.collect { loggedIn -> if (!loggedIn) disconnect() }
            }
        }

        fun connect() {
            scope.launch {
                connectionLock.withLock {
                    if (connectionJob?.isActive == true) return@withLock
                    connectionJob = scope.launch { runConnectionLoop() }
                }
            }
        }

        /** Suspends until the connection is actually torn down (not just cancellation *requested*) -
         *  callers that immediately [connect] again afterwards must not race the old job's own
         *  cleanup (its `finally { stompSession.disconnect() }}`). */
        suspend fun disconnect() {
            connectionLock.withLock {
                connectionJob?.cancelAndJoin()
                connectionJob = null
                backoff.reset()
            }
        }

        private suspend fun runConnectionLoop() {
            while (currentCoroutineContext().isActive) {
                val outcome = runCatching { connectOnce() }
                if (outcome.isFailure) {
                    realtimeEvents.emit(RealtimeEvent.Disconnected)
                }
                delay(backoff.nextDelayMillis())
            }
        }

        private suspend fun connectOnce() {
            val storedToken = tokenStore.getAccessToken() ?: return
            val token = ensureFresh(storedToken) ?: return
            val wsUrl = wsUrlFromApiBaseUrl(BuildConfig.API_BASE_URL)
            val skew = sampleServerClockSkew()

            val stompSession =
                try {
                    stompSessionFactory.connect(wsUrl, mapOf("Authorization" to "Bearer $token"))
                } catch (e: WebSocketConnectionException) {
                    if (e.httpStatusCode != HTTP_UNAUTHORIZED) throw e
                    val refreshedToken = tokenRefresher.refresh(token) ?: throw e
                    stompSessionFactory.connect(wsUrl, mapOf("Authorization" to "Bearer $refreshedToken"))
                }

            backoff.reset()
            runSubscriptions(stompSession, skew)
        }

        private suspend fun runSubscriptions(
            stompSession: StompSession,
            skew: ServerClockSkew,
        ) {
            try {
                coroutineScope {
                    launch { stompSession.subscribeText(OFFERS_DESTINATION).collect { handleOfferFrame(it, skew) } }
                    launch { stompSession.subscribeText(BOOKING_DESTINATION).collect { handleBookingFrame(it) } }
                    // Emitted after both SUBSCRIBE sends are already in flight (design doc §2.2:
                    // subscribe, *then* let reconciliation fetches happen) - not a perfect guarantee
                    // without receipts, but far closer than emitting before subscribing was even
                    // attempted (fresh-context review finding).
                    realtimeEvents.emit(RealtimeEvent.Connected(skew))
                }
            } finally {
                // withContext(NonCancellable): this finally runs because the surrounding coroutine was
                // cancelled (disconnect(), or the connection dropping) - stompSession.disconnect()
                // suspends (it waits for a DISCONNECT receipt before closing the socket), and without
                // NonCancellable that suspension immediately throws CancellationException, which
                // runCatching swallows, meaning the socket is never actually closed. Left unfixed, this
                // leaks a live connection on every toggle/logout until the per-user connection limit
                // (websocket.max_connections_per_user, default 3) locks the driver out entirely
                // (fresh-context review finding, CRITICAL).
                withContext(NonCancellable) {
                    runCatching { stompSession.disconnect() }
                }
            }
        }

        private suspend fun handleOfferFrame(
            json: String,
            skew: ServerClockSkew,
        ) {
            // No PII/offer content ever logged here (design doc §4) - a malformed frame is simply
            // dropped, never surfaced, never logged with its raw content.
            val offer = runCatching { moshi.adapter(OfferResponse::class.java).fromJson(json) }.getOrNull() ?: return
            realtimeEvents.emit(RealtimeEvent.OfferReceived(offer, skew))
        }

        private suspend fun handleBookingFrame(json: String) {
            val booking =
                runCatching { moshi.adapter(BookingDetailResponse::class.java).fromJson(json) }.getOrNull() ?: return
            realtimeEvents.emit(RealtimeEvent.BookingUpdated(booking))
        }

        /** Refreshes only if the token is positively known to be expired or near-expiry - an
         *  undecodable `exp` claim is treated as "unknown, proceed as-is" rather than forced into a
         *  refresh, so a decode quirk alone can't trigger a refresh storm. A token that's actually bad
         *  still gets caught by the 401-at-handshake path in [connectOnce]. */
        private suspend fun ensureFresh(accessToken: String): String? {
            val expirySeconds = jwtExpiryEpochSeconds(accessToken, moshi)
            val nowSeconds = System.currentTimeMillis() / MILLIS_PER_SECOND
            val nearOrPastExpiry = expirySeconds != null && expirySeconds - nowSeconds <= PROACTIVE_REFRESH_MARGIN_SECONDS
            return if (nearOrPastExpiry) tokenRefresher.refresh(accessToken) else accessToken
        }

        /** Sampled once per connection attempt (design doc §2.2), not per offer - sent alongside every
         *  [RealtimeEvent.OfferReceived] from this connection so the offer screen's countdown corrects
         *  for clock skew without re-measuring it per push. Falls back to [ServerClockSkew.NONE] if
         *  the ping fails or the response has no parseable `Date` header - a missed skew sample should
         *  never block connecting. */
        private suspend fun sampleServerClockSkew(): ServerClockSkew {
            val localNowBefore = System.currentTimeMillis()
            val response = runCatching { systemApi.ping() }.getOrNull() ?: return ServerClockSkew.NONE
            val localNowAfter = System.currentTimeMillis()
            val serverDate = response.raw().headers.getDate("Date") ?: return ServerClockSkew.NONE
            // Half-RTT approximation of "local time when the server actually stamped that header" -
            // not exact, but closer than attributing the whole round trip to skew.
            val midpoint = (localNowBefore + localNowAfter) / 2
            return ServerClockSkew.sample(serverEpochMillisAtResponse = serverDate.time, localNowMillisAtReceipt = midpoint)
        }

        private companion object {
            const val OFFERS_DESTINATION = "/user/queue/offers"
            const val BOOKING_DESTINATION = "/user/queue/booking"
            const val HTTP_UNAUTHORIZED = 401

            // Matches design doc §3 rule 6's "~5-minutes-before-expiry" schedule.
            const val PROACTIVE_REFRESH_MARGIN_SECONDS = 300L
            const val MILLIS_PER_SECOND = 1_000L
        }
    }
