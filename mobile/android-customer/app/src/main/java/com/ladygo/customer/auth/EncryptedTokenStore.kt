package com.ladygo.customer.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EncryptedTokenStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : TokenStore {
        // Lazy, not an eager property initializer: constructing this class (which Hilt does for
        // every injection site in the authenticated-request graph) must not itself touch the
        // Android Keystore - only an actual token read/write should. Confirmed by a real failure
        // this caused: Robolectric has no AndroidKeyStore provider, so building this object eagerly
        // crashed (KeyStoreException/NoSuchAlgorithmException) for a Hilt-injected service
        // (any Hilt-created singleton) whose test never even calls getAccessToken()/setTokens().
        private val masterKey by lazy {
            MasterKey
                .Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        }

        private val prefs: SharedPreferences by lazy {
            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

        @Synchronized
        override fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)

        @Synchronized
        override fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH_TOKEN, null)

        @Synchronized
        override fun setTokens(
            accessToken: String,
            refreshToken: String,
        ) {
            prefs
                .edit()
                .putString(KEY_ACCESS_TOKEN, accessToken)
                .putString(KEY_REFRESH_TOKEN, refreshToken)
                .apply()
        }

        @Synchronized
        override fun clear() {
            prefs.edit().clear().apply()
        }

        private companion object {
            const val PREFS_FILE_NAME = "ladygo_customer_session"
            const val KEY_ACCESS_TOKEN = "access_token"
            const val KEY_REFRESH_TOKEN = "refresh_token"
        }
    }
