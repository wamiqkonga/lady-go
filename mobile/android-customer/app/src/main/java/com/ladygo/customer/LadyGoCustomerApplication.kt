package com.ladygo.customer

import android.app.Application
import com.ladygo.customer.push.RideNotifications
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class LadyGoCustomerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // FCM shows background notifications into this channel, so it must exist before any push arrives.
        RideNotifications.ensureChannel(this)
    }
}
