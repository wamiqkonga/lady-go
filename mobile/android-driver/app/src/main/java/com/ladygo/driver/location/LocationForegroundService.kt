package com.ladygo.driver.location

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.ladygo.driver.MainActivity
import com.ladygo.driver.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/**
 * Runs only while the driver is ONLINE, started only from a visible activity (see
 * [com.ladygo.driver.ui.HomeViewModel]) - stopped on an explicit OFFLINE toggle, logout, forced
 * logout, or the server itself rejecting a location update because this driver is OFFLINE
 * server-side (§2.3 of the design doc; see [LocationUploadRepository]). Once started it keeps
 * running - and keeps receiving location updates - after the app is backgrounded; that is Android's
 * documented foreground-service exemption from background-location restrictions, not something this
 * service has to do anything special for.
 *
 * No PII or location data is ever logged here - only lifecycle/outcome shapes, if anything.
 */
@AndroidEntryPoint
class LocationForegroundService : Service() {
    @Inject
    lateinit var locationUploadRepository: LocationUploadRepository

    @Inject
    lateinit var locationServiceEvents: LocationServiceEvents

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = LocationUploadGate(baseIntervalMillis = THROTTLE_INTERVAL_MILLIS)
    private var locationCallback: LocationCallback? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        startLocationUpdates()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // Lost the permission after the service was already started (e.g. revoked from system
            // settings mid-session) - stop rather than run a service that can never receive a fix.
            stopSelf()
            return
        }
        val request =
            LocationRequest
                .Builder(Priority.PRIORITY_HIGH_ACCURACY, THROTTLE_INTERVAL_MILLIS)
                .setMinUpdateIntervalMillis(THROTTLE_INTERVAL_MILLIS)
                .build()
        val callback =
            object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let(::handleNewLocation)
                }
            }
        locationCallback = callback
        fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    private fun handleNewLocation(location: Location) {
        // No queue: only ever the latest fix matters, and an older one still waiting out the
        // throttle/backoff window is simply superseded here, never sent late (§2.3).
        if (!gate.canSendNow()) return
        if (!location.hasAccuracy()) return

        serviceScope.launch {
            when (
                locationUploadRepository.send(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracyMeters = location.accuracy.toDouble(),
                    recordedAt = Instant.ofEpochMilli(location.time),
                )
            ) {
                LocationSendOutcome.Sent -> gate.onSendSucceeded()
                is LocationSendOutcome.Failed -> gate.onSendFailed()
                LocationSendOutcome.DriverSetOffline -> {
                    gate.onSendFailed()
                    locationServiceEvents.emit(LocationServiceEvent.DriverSetOfflineByServer)
                    stopSelf()
                }
            }
        }
    }

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.location_sharing_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openAppIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat
            .Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.location_sharing_notification_title))
            .setContentText(getString(R.string.location_sharing_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .build()
    }

    companion object {
        const val THROTTLE_INTERVAL_MILLIS = 2_000L
        private const val NOTIFICATION_CHANNEL_ID = "location_sharing"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            context.startForegroundService(Intent(context, LocationForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocationForegroundService::class.java))
        }
    }
}
