package com.ladygo.customer.booking

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Plain intents only - no CALL_PHONE permission. */
object ExternalIntents {
    /** `ACTION_DIAL` opens the dialer pre-filled; the rider makes the final tap. No backend involvement. */
    fun dialEmergency(context: Context): Boolean =
        try {
            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
}
