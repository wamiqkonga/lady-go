package com.ladygo.customer.auth

/**
 * Durable session storage. Tokens live here (not in memory only, unlike the customer web
 * website's deliberate memory-only choice) because a mobile app is expected to survive process
 * death without forcing a fresh OTP every time, and the app's own sandboxed data directory - unlike
 * a browser tab - has no equivalent external access surface to defend against. Backed by
 * EncryptedSharedPreferences/Android Keystore in [EncryptedTokenStore]; a fake in-memory
 * implementation is used in tests.
 */
interface TokenStore {
    fun getAccessToken(): String?

    fun getRefreshToken(): String?

    fun setTokens(
        accessToken: String,
        refreshToken: String,
    )

    fun clear()
}
