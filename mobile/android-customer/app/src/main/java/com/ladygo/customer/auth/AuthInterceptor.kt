package com.ladygo.customer.auth

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/** Attaches the current access token to every request on the authenticated client. A request made
 *  before any login (no token yet) is sent as-is and the server's own 401 drives the normal flow. */
class AuthInterceptor
    @Inject
    constructor(
        private val tokenStore: TokenStore,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val token = tokenStore.getAccessToken()
            val request = chain.request()
            return if (token == null) {
                chain.proceed(request)
            } else {
                chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
            }
        }
    }
