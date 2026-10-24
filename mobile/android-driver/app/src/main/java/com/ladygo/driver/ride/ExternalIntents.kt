package com.ladygo.driver.ride

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Plain intents only - no CALL_PHONE permission, no maps SDK/key (design doc §4, §6.1). */
object ExternalIntents {
    /** `ACTION_DIAL` opens the dialer pre-filled; the driver makes the final tap. No backend involvement. */
    fun dialEmergency(context: Context): Boolean = launch(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")))

    /** Opens the dialer pre-filled with [number] (the support line); the driver makes the final tap. */
    fun dial(
        context: Context,
        number: String,
    ): Boolean = launch(context, Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))

    /** Opens a WhatsApp chat via the universal link; [digits] is the number with no `+`, spaces or dashes. */
    fun openWhatsApp(
        context: Context,
        digits: String,
    ): Boolean = launch(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits")))

    /** Hand-off to Google Maps turn-by-turn by address, falling back to any `geo:` handler. */
    fun navigateTo(
        context: Context,
        address: String,
    ): Boolean {
        val encoded = Uri.encode(address)
        val maps = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$encoded"))
        return launch(context, maps) || launch(context, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded")))
    }

    private fun launch(
        context: Context,
        intent: Intent,
    ): Boolean =
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
}
