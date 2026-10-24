package com.ladygo.driver.onboarding.ui

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.ladygo.driver.BuildConfig

/** The one way to change documents, vehicle or application details: hand over to the Lady Go driver website. */
fun openDriverWebsite(context: Context) {
    context.startActivity(Intent(Intent.ACTION_VIEW, BuildConfig.DRIVER_REGISTRATION_URL.toUri()))
}
