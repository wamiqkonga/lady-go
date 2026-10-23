package com.ladygo.driver.offer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.ladygo.driver.MainActivity
import com.ladygo.driver.R
import com.ladygo.driver.api.generated.models.OfferResponse
import com.ladygo.driver.ui.components.Money
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The high-priority push-alert for an incoming offer (design doc §1: loud notification/vibration,
 * in addition to the in-app full-screen offer screen), separate from the `location_sharing` channel
 * ([com.ladygo.driver.location.LocationForegroundService]) so its own importance doesn't get diluted
 * by that channel's deliberately low one. `IMPORTANCE_HIGH` plus a sound and vibration is what makes
 * this a heads-up alert - no `setFullScreenIntent`/`USE_FULL_SCREEN_INTENT`, which is a separate,
 * more invasive permission this task never asked for.
 *
 * Content deliberately never reveals the exact pickup (design doc §4's "approximate only, like the
 * screen" rule) - it shows the fare, never coordinates or an address; [OfferResponse] itself only
 * ever carries the grid-snapped approximate pickup anyway, so there is no exact location available
 * to accidentally leak here even if this changed.
 */
@Singleton
class OfferNotifier
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        init {
            createNotificationChannelIfNeeded()
        }

        fun notify(offer: OfferResponse) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val openAppIntent =
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            val fareText = formatFare(offer)
            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setContentTitle(context.getString(R.string.offer_notification_title))
                    .setContentText(fareText)
                    .setSmallIcon(android.R.drawable.ic_popup_reminder)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setAutoCancel(false)
                    .setOngoing(true)
                    .setContentIntent(openAppIntent)
                    .build()
            manager.notify(NOTIFICATION_ID, notification)
        }

        fun cancel() {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }

        internal fun formatFare(offer: OfferResponse): String {
            val fare = offer.estimatedFare
            val currency = offer.currency
            return if (fare != null && currency != null) {
                context.getString(R.string.offer_notification_text_with_fare, Money.format(fare, currency))
            } else {
                context.getString(R.string.offer_notification_text_generic)
            }
        }

        private fun createNotificationChannelIfNeeded() {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.offer_notification_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    enableVibration(true)
                }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        private companion object {
            const val CHANNEL_ID = "ride_offers"
            const val NOTIFICATION_ID = 2001
        }
    }
