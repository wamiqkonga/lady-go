package com.ladygo.driver.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class LadyGoMessagingService : FirebaseMessagingService() {
    @Inject
    lateinit var registrar: PushTokenRegistrar

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Firebase rotated this phone's token: tell the server (it ignores the call when nobody is signed in). */
    override fun onNewToken(token: String) {
        scope.launch { registrar.sync() }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        RideNotifications.show(
            context = this,
            title = message.notification?.title,
            body = message.notification?.body,
            bookingId = message.data["bookingId"],
        )
    }
}
