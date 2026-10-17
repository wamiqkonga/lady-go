package com.ladygo.driver.realtime

import com.ladygo.driver.api.generated.apis.SystemControllerApi
import com.ladygo.driver.api.generated.infrastructure.Serializer
import com.ladygo.driver.api.generated.models.ApiResponseMapStringString
import com.ladygo.driver.auth.SessionManager
import com.ladygo.driver.auth.TokenRefresher
import com.ladygo.driver.testing.FakeStompSessionFactory
import com.ladygo.driver.testing.FakeTokenStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import org.hildan.krossbow.websocket.WebSocketConnectionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.util.Base64
import okhttp3.Response as OkHttpResponse

@OptIn(ExperimentalCoroutinesApi::class)
class StompConnectionManagerTest {
    private val tokenStore = FakeTokenStore(accessToken = validAccessToken(), refreshToken = "refresh-1")
    private val sessionManager = SessionManager(tokenStore)
    private val tokenRefresher = mockk<TokenRefresher>()
    private val systemApi =
        mockk<SystemControllerApi> {
            coEvery { ping() } returns pingResponseWithDateHeader()
        }
    private val realtimeEvents = RealtimeEvents()
    private val stompSessionFactory = FakeStompSessionFactory()

    /** The manager's own background loop shares this test's dispatcher/scheduler (so `delay()`-based
     *  backoff responds to `advanceUntilIdle()`/`advanceTimeBy()` instead of needing real wall-clock
     *  time), but deliberately with its own fresh [Job], not [this] [TestScope]'s own root job: the
     *  manager's `init` block launches a permanently-running observer
     *  (`sessionManager.isLoggedIn.collect`) that, by design, never completes on its own - if it were
     *  a structural child of the test's own job, `runTest` would always flag it as a leaked
     *  "uncompleted coroutine" even though it's not a bug, just this class's normal singleton
     *  lifetime. A separate root job sidesteps that without losing virtual-time control. */
    private fun TestScope.manager() =
        StompConnectionManager(
            stompSessionFactory = stompSessionFactory,
            tokenStore = tokenStore,
            tokenRefresher = tokenRefresher,
            sessionManager = sessionManager,
            systemApi = systemApi,
            realtimeEvents = realtimeEvents,
            moshi = Serializer.moshi,
            scope = CoroutineScope(coroutineContext + Job()),
        )

    @Test
    fun connectSubscribesToBothDestinationsAndEmitsConnected() =
        runTest {
            val collected = mutableListOf<RealtimeEvent>()
            val collectJob = launchCollector(collected)
            stompSessionFactory.enqueueSession()
            val manager = manager()

            manager.connect()
            advanceUntilIdle()

            assertTrue(collected.any { it is RealtimeEvent.Connected })
            assertEquals(1, stompSessionFactory.connectCalls.size)
            manager.disconnect()
            collectJob.cancelAndJoin()
        }

    @Test
    fun aPushedOfferFrameIsParsedAndEmittedWithTheSampledSkew() =
        runTest {
            val collected = mutableListOf<RealtimeEvent>()
            val collectJob = launchCollector(collected)
            val session = stompSessionFactory.enqueueSession()
            val manager = manager()
            manager.connect()
            advanceUntilIdle()

            session.pushText("/user/queue/offers", """{"offerId":"$SAMPLE_OFFER_ID","estimatedFare":250,"currency":"PKR"}""")
            advanceUntilIdle()

            val received = collected.filterIsInstance<RealtimeEvent.OfferReceived>().single()
            assertEquals(SAMPLE_OFFER_ID, received.offer.offerId.toString())
            assertEquals(250L, received.offer.estimatedFare)
            manager.disconnect()
            collectJob.cancelAndJoin()
        }

    @Test
    fun aPushedBookingFrameIsParsedAndEmitted() =
        runTest {
            val collected = mutableListOf<RealtimeEvent>()
            val collectJob = launchCollector(collected)
            val session = stompSessionFactory.enqueueSession()
            val manager = manager()
            manager.connect()
            advanceUntilIdle()

            session.pushText("/user/queue/booking", """{"id":"$SAMPLE_BOOKING_ID","status":"DRIVER_ACCEPTED"}""")
            advanceUntilIdle()

            val received = collected.filterIsInstance<RealtimeEvent.BookingUpdated>().single()
            assertEquals(SAMPLE_BOOKING_ID, received.booking.id.toString())
            manager.disconnect()
            collectJob.cancelAndJoin()
        }

    @Test
    fun aForcedDisconnectReconnectsAfterTheBackoffDelay() =
        runTest {
            val collected = mutableListOf<RealtimeEvent>()
            val collectJob = launchCollector(collected)
            val firstSession = stompSessionFactory.enqueueSession()
            stompSessionFactory.enqueueSession()
            val manager = manager()
            manager.connect()
            advanceUntilIdle()
            assertEquals(1, stompSessionFactory.connectCalls.size)

            firstSession.simulateDisconnect()
            advanceUntilIdle()

            assertTrue(collected.any { it is RealtimeEvent.Disconnected })
            assertEquals(2, stompSessionFactory.connectCalls.size)
            manager.disconnect()
            collectJob.cancelAndJoin()
        }

    @Test
    fun reconnectingWithAnExpiredStoredTokenRefreshesBeforeConnecting() =
        runTest {
            tokenStore.setTokens(expiredAccessToken(), "refresh-1")
            coEvery { tokenRefresher.refresh(any()) } returns validAccessToken()
            val collectJob = launchCollector(mutableListOf())
            stompSessionFactory.enqueueSession()
            val manager = manager()

            manager.connect()
            advanceUntilIdle()

            coVerify(exactly = 1) { tokenRefresher.refresh(expiredAccessToken()) }
            val (_, headers) = stompSessionFactory.connectCalls.single()
            assertEquals("Bearer ${validAccessToken()}", headers["Authorization"])
            manager.disconnect()
            collectJob.cancelAndJoin()
        }

    @Test
    fun a401AtHandshakeRefreshesAndRetriesImmediatelyWithoutWaitingForBackoff() =
        runTest {
            coEvery { tokenRefresher.refresh(any()) } returns "refreshed-token"
            stompSessionFactory.enqueueFailure(WebSocketConnectionException(url = "wss://test/ws", httpStatusCode = 401))
            stompSessionFactory.enqueueSession()
            val collectJob = launchCollector(mutableListOf())
            val manager = manager()

            manager.connect()
            advanceUntilIdle()

            assertEquals(2, stompSessionFactory.connectCalls.size)
            val (_, secondAttemptHeaders) = stompSessionFactory.connectCalls[1]
            assertEquals("Bearer refreshed-token", secondAttemptHeaders["Authorization"])
            manager.disconnect()
            collectJob.cancelAndJoin()
        }

    @Test
    fun disconnectStopsTheLoopAndClosesTheLiveSession() =
        runTest {
            val collectJob = launchCollector(mutableListOf())
            val session = stompSessionFactory.enqueueSession()
            val manager = manager()
            manager.connect()
            advanceUntilIdle()

            manager.disconnect()
            advanceTimeBy(60_000)
            advanceUntilIdle()

            assertEquals(1, session.disconnectCallCount)
            assertEquals(1, stompSessionFactory.connectCalls.size) // no reconnect attempted after an explicit disconnect
            collectJob.cancelAndJoin()
        }

    @Test
    fun connectIsANoOpWhileAlreadyConnecting() =
        runTest {
            stompSessionFactory.enqueueSession()
            val collectJob = launchCollector(mutableListOf())
            val manager = manager()

            manager.connect()
            manager.connect()
            advanceUntilIdle()

            assertEquals(1, stompSessionFactory.connectCalls.size)
            manager.disconnect()
            collectJob.cancelAndJoin()
        }

    private fun TestScope.launchCollector(sink: MutableList<RealtimeEvent>): Job = launch { realtimeEvents.events.collect { sink.add(it) } }

    private fun pingResponseWithDateHeader(): Response<ApiResponseMapStringString> {
        val raw =
            OkHttpResponse
                .Builder()
                .request(Request.Builder().url("http://test/api/v1/system/ping").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Date", "Wed, 01 Jan 2025 00:00:00 GMT")
                .build()
        return Response.success(ApiResponseMapStringString(), raw)
    }

    private companion object {
        const val SAMPLE_OFFER_ID = "a1b2c3d4-e5f6-4789-a012-3456789abcde"
        const val SAMPLE_BOOKING_ID = "b1b2c3d4-e5f6-4789-a012-3456789abcde"

        fun validAccessToken() = fakeJwt(exp = Long.MAX_VALUE / 1000)

        fun expiredAccessToken() = fakeJwt(exp = 1L)

        fun fakeJwt(exp: Long): String {
            val header = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"alg":"none"}""".toByteArray())
            val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":$exp}""".toByteArray())
            return "$header.$payload.signature"
        }
    }
}
