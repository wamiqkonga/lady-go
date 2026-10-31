package com.ladygo.driver.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.ladygo.driver.api.generated.apis.DeviceControllerApi
import com.ladygo.driver.api.generated.models.RegisterDeviceRequest
import com.ladygo.driver.api.generated.models.UnregisterDeviceRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Keeps the server's record of this phone's push token current. Push is optional at build time: without the Firebase
 * config file there is no FirebaseApp, and every call here is a quiet no-op. Nothing here ever throws at a caller.
 */
@Singleton
class PushTokenRegistrar
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val api: DeviceControllerApi,
    ) {
        private val available: Boolean get() = FirebaseApp.getApps(context).isNotEmpty()

        /** Registers the current token for the signed-in user. Call after sign-in, on app start while signed in, and when the token rotates. */
        suspend fun sync() {
            if (!available) return
            val token = currentToken() ?: return
            attempt { api.register(RegisterDeviceRequest(token = token, platform = RegisterDeviceRequest.Platform.ANDROID)) }
        }

        /** Sign-out: stop pushes to this phone. Best effort, and must run while the access token is still valid. */
        suspend fun forget() {
            if (!available) return
            val token = currentToken() ?: return
            attempt { api.unregister(UnregisterDeviceRequest(token = token)) }
        }

        private suspend fun attempt(call: suspend () -> Unit) {
            try {
                call()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Offline or server error: the next app start or token refresh registers again.
            }
        }

        private suspend fun currentToken(): String? =
            suspendCancellableCoroutine { continuation ->
                FirebaseMessaging
                    .getInstance()
                    .token
                    .addOnCompleteListener { task -> continuation.resume(if (task.isSuccessful) task.result else null) }
            }
    }
