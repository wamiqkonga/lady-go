package com.ladygo.customer.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the app currently holds a session, derived from [TokenStore]. Navigation observes this
 * to decide whether to show Login or the onboarding gate - it is never asked for the tokens
 * themselves (those stay inside [TokenStore]/[AuthRepository]/the network layer).
 */
@Singleton
class SessionManager
    @Inject
    constructor(
        private val tokenStore: TokenStore,
    ) {
        private val _isLoggedIn = MutableStateFlow(tokenStore.getRefreshToken() != null)
        val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

        fun onLoggedIn() {
            _isLoggedIn.value = true
        }

        /** Called both for a user-initiated logout and a forced logout (refresh failed - see
         *  [TokenAuthenticator]). Either way the only correct recovery is a fresh OTP login. */
        fun onLoggedOut() {
            tokenStore.clear()
            _isLoggedIn.value = false
        }
    }
