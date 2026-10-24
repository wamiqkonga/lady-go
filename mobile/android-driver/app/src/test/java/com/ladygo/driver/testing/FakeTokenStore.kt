package com.ladygo.driver.testing

import com.ladygo.driver.auth.TokenStore

class FakeTokenStore(
    accessToken: String? = null,
    refreshToken: String? = null,
) : TokenStore {
    private var access: String? = accessToken
    private var refresh: String? = refreshToken

    override fun getAccessToken(): String? = access

    override fun getRefreshToken(): String? = refresh

    override fun setTokens(
        accessToken: String,
        refreshToken: String,
    ) {
        access = accessToken
        refresh = refreshToken
    }

    override fun clear() {
        access = null
        refresh = null
    }
}
