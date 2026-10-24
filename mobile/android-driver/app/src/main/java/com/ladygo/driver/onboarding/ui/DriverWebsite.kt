package com.ladygo.driver.onboarding.ui

import android.content.Context
import android.widget.Toast
import com.ladygo.driver.BuildConfig
import com.ladygo.driver.R
import com.ladygo.driver.ride.ExternalIntents

/** The one way to change documents, vehicle or application details: hand over to the Lady Go driver website. */
fun openDriverWebsite(context: Context): Boolean {
    val opened = ExternalIntents.openUrl(context, BuildConfig.DRIVER_REGISTRATION_URL)
    if (!opened) Toast.makeText(context, context.getString(R.string.common_no_browser), Toast.LENGTH_LONG).show()
    return opened
}
