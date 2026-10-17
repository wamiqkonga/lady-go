package com.ladygo.driver.auth

import com.ladygo.driver.api.generated.apis.AuthControllerApi
import com.ladygo.driver.api.generated.models.RefreshRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single-flight refresh-token rotation, shared by [TokenAuthenticator] (reactive, on an HTTP 401) and
 * [com.ladygo.driver.realtime.StompConnectionManager] (proactive, before every WebSocket connect
 * attempt - design doc §2.2/§3 rule 6: "refreshes the token before expiry"). Pulled out of
 * [TokenAuthenticator] rather than duplicated, since both callers need the exact same race-safety:
 * whichever caller wins the lock does the real network refresh; a caller that loses the race finds
 * the token [tokenStore] already rotated to by the winner and just uses that, instead of firing a
 * second refresh call ("no refresh storms").
 */
@Singleton
class TokenRefresher
    @Inject
    constructor(
        private val tokenStore: TokenStore,
        private val sessionManager: SessionManager,
        private val authApi: AuthControllerApi,
    ) {
        private val mutex = Mutex()

        /**
         * [currentAccessToken] is whatever token the caller observed as stale/about-to-expire/already
         * rejected - passed in (rather than re-read inside the lock by this function alone) so a
         * caller that loses the race can tell "the token changed under me, use the new one" apart from
         * "it's still the same stale token, I really do need to refresh it."
         *
         * Returns the fresh access token, or null if the refresh attempt didn't produce one - either
         * because the refresh token itself was definitively rejected (a real 400/401 from
         * `/auth/refresh`, in which case this has already logged the session out via
         * [SessionManager.onLoggedOut]), or because the attempt simply didn't reach the server
         * (network failure, 5xx, or this coroutine itself being cancelled) - a transient failure,
         * deliberately *not* treated as "the refresh token is invalid." The two used to be
         * indistinguishable here: a network blip while proactively refreshing (the common case for
         * [com.ladygo.driver.realtime.StompConnectionManager], which refreshes from inside its own
         * reconnect loop - i.e. exactly when the network is most likely down) logged the driver out
         * for no real reason, and could even trip the server's refresh-token-reuse detection if the
         * "failed" response had actually rotated the token server-side before being lost
         * (fresh-context review finding, CRITICAL).
         */
        suspend fun refresh(currentAccessToken: String?): String? =
            mutex.withLock {
                val latest = tokenStore.getAccessToken()
                if (latest != null && latest != currentAccessToken) {
                    // Another caller already refreshed while this one was waiting for the lock.
                    return@withLock latest
                }
                val refreshToken = tokenStore.getRefreshToken() ?: return@withLock null

                val response =
                    runCatching { authApi.refresh(RefreshRequest(refreshToken = refreshToken)) }
                        .getOrElse { e ->
                            // Never swallow cancellation - this coroutine is being cancelled, not "rejected".
                            // Anything else (e.g. IOException) is transient - not a verdict on the refresh token.
                            if (e is CancellationException) throw e
                            return@withLock null
                        }

                if (!response.isSuccessful) {
                    return@withLock if (isDefinitiveRejection(response)) {
                        sessionManager.onLoggedOut()
                        null
                    } else {
                        null // 5xx or similar - transient, do not log out
                    }
                }

                val tokens = response.body()?.data
                val newAccessToken = tokens?.accessToken
                val newRefreshToken = tokens?.refreshToken
                if (newAccessToken == null || newRefreshToken == null) {
                    // A 2xx with no usable tokens is a malformed/unexpected response, not evidence the
                    // refresh token itself was rejected - treated the same as a transient failure.
                    return@withLock null
                }
                tokenStore.setTokens(newAccessToken, newRefreshToken)
                newAccessToken
            }

        private fun isDefinitiveRejection(response: Response<*>): Boolean = response.code() in DEFINITIVE_REJECTION_CODES

        private companion object {
            val DEFINITIVE_REJECTION_CODES = 400..401
        }
    }
