package com.ladygo.driver.auth

import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject

/**
 * Reacts to a 401 on the authenticated client by rotating the refresh token (via [TokenRefresher],
 * shared with [com.ladygo.driver.realtime.StompConnectionManager]'s proactive refresh), retrying the
 * original request once with the new access token.
 */
class TokenAuthenticator
    @Inject
    constructor(
        private val tokenRefresher: TokenRefresher,
    ) : Authenticator {
        override fun authenticate(
            route: Route?,
            response: Response,
        ): Request? {
            if (responseCount(response) >= 2) {
                // Already retried once for this request chain and failed again - the new token isn't
                // good either, so retrying forever would just loop. Give up and let the 401 surface.
                return null
            }
            val tokenUsedByFailingRequest = response.request.header("Authorization")?.removePrefix("Bearer ")
            val newAccessToken = runBlocking { tokenRefresher.refresh(tokenUsedByFailingRequest) } ?: return null
            return response.request
                .newBuilder()
                .header("Authorization", "Bearer $newAccessToken")
                .build()
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
