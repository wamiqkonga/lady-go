package com.ladygo.driver.auth

import com.ladygo.driver.api.generated.apis.AuthControllerApi
import com.ladygo.driver.api.generated.models.RefreshRequest
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject

/**
 * Reacts to a 401 on the authenticated client by rotating the refresh token, retrying the original
 * request once with the new access token. Single-flight: OkHttp calls [authenticate] from whichever
 * thread hit the 401, and multiple requests can fail at once (e.g. two screens' calls racing), so
 * this synchronizes on [lock] and re-checks the stored access token *inside* the lock before calling
 * the network - a thread that loses the race to acquire the lock finds the token already rotated by
 * the thread that won it, and just retries with that instead of firing a second refresh call
 * ("no refresh storms", per the task).
 */
class TokenAuthenticator
    @Inject
    constructor(
        private val tokenStore: TokenStore,
        private val sessionManager: SessionManager,
        private val authApi: AuthControllerApi,
    ) : Authenticator {
        private val lock = Any()

        override fun authenticate(
            route: Route?,
            response: Response,
        ): Request? {
            if (responseCount(response) >= 2) {
                // Already retried once for this request chain and failed again - the new token isn't
                // good either, so retrying forever would just loop. Give up and let the 401 surface.
                return null
            }
            if (tokenStore.getRefreshToken() == null) return null

            synchronized(lock) {
                val tokenUsedByFailingRequest = response.request.header("Authorization")?.removePrefix("Bearer ")
                val currentAccessToken = tokenStore.getAccessToken()
                if (currentAccessToken != null && currentAccessToken != tokenUsedByFailingRequest) {
                    // Another thread already refreshed while we were waiting for the lock.
                    return response.request
                        .newBuilder()
                        .header("Authorization", "Bearer $currentAccessToken")
                        .build()
                }

                val refreshToken = tokenStore.getRefreshToken() ?: return null
                val refreshed =
                    runBlocking {
                        runCatching { authApi.refresh(RefreshRequest(refreshToken = refreshToken)) }.getOrNull()
                    }
                val tokens = refreshed?.takeIf { it.isSuccessful }?.body()?.data
                val newAccessToken = tokens?.accessToken
                val newRefreshToken = tokens?.refreshToken
                if (newAccessToken == null || newRefreshToken == null) {
                    sessionManager.onLoggedOut()
                    return null
                }
                tokenStore.setTokens(newAccessToken, newRefreshToken)
                return response.request
                    .newBuilder()
                    .header("Authorization", "Bearer $newAccessToken")
                    .build()
            }
        }

        private fun responseCount(response: Response): Int {
            var count = 1
            var prior = response.priorResponse
            while (prior != null) {
                count++
                prior = prior.priorResponse
            }
            return count
        }
    }
