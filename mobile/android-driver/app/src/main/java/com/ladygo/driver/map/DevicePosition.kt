package com.ladygo.driver.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.model.LatLng

/**
 * The driver's own position for the on-screen maps, updated every few seconds while the map is on screen and
 * released as soon as it leaves. This is display-only: what the server learns about her position still comes only from
 * the foreground location service, so showing a map adds no new reporting.
 */
@Composable
fun rememberDevicePosition(): State<LatLng?> {
    val context = LocalContext.current
    val position = remember { mutableStateOf<LatLng?>(null) }
    DisposableEffect(context) {
        val allowed =
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        if (!allowed) return@DisposableEffect onDispose {}
        val client = LocationServices.getFusedLocationProviderClient(context)
        val request =
            LocationRequest
                .Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, UPDATE_INTERVAL_MILLIS)
                .setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MILLIS)
                .build()
        val callback =
            object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { position.value = LatLng(it.latitude, it.longitude) }
                }
            }
        @SuppressLint("MissingPermission") // checked above
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        onDispose { client.removeLocationUpdates(callback) }
    }
    return position
}

private const val UPDATE_INTERVAL_MILLIS = 5_000L
private const val MIN_UPDATE_INTERVAL_MILLIS = 2_000L
