package com.ladygo.driver.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ladygo.driver.MainActivity
import com.ladygo.driver.R

/** Shows ride updates. The server sends only generic text and ids, so nothing sensitive reaches a locked screen. */
object RideNotifications {
    const val CHANNEL_ID = "ride_updates"

    /** Safe to call repeatedly; the channel must exist before any notification (and before FCM shows one in the background). */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_ride_updates),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.notification_channel_ride_updates_description) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** FCM only draws notifications itself while the app is in the background; in the foreground the app draws this one. */
    fun show(
        context: Context,
        title: String?,
        body: String?,
        bookingId: String?,
    ) {
        if (title == null && body == null) return
        val permitted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!permitted) return
        ensureChannel(context)
        val open =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_ladygo)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
        // One notification per ride, so a later update replaces the earlier one instead of stacking.
        NotificationManagerCompat.from(context).notify((bookingId ?: title).hashCode(), notification)
    }
}
