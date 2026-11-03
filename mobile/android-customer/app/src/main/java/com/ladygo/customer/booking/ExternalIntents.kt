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

    /** Opens the messages app with the text ready for her to send - nothing is sent without her tap, and the app
     *  needs no SMS permission. */
    fun composeSms(
        context: Context,
        phones: List<String>,
        body: String,
    ): Boolean =
        try {
            val intent =
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + phones.joinToString(";")))
                    .putExtra("sms_body", body)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }

    /** The system share sheet with plain text. */
    fun shareText(
        context: Context,
        text: String,
        chooserTitle: String,
    ): Boolean =
        try {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
}
