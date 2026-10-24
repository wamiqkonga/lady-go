package com.ladygo.customer.booking

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

sealed interface CurrentLocation {
    data class Found(
        val point: GeoPoint,
    ) : CurrentLocation

    data object PermissionDenied : CurrentLocation

    /** Permission is granted but no fix could be obtained (location services off, no signal...). */
    data object Unavailable : CurrentLocation
}

/** Where the rider is right now - the pickup point. An interface so the booking flow is testable
 *  without Play services. */
interface LocationProvider {
    suspend fun current(): CurrentLocation
}

@Singleton
class FusedLocationProvider
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : LocationProvider {
        override suspend fun current(): CurrentLocation {
            val granted =
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
            if (!granted) return CurrentLocation.PermissionDenied

            val client = LocationServices.getFusedLocationProviderClient(context)
            val tokenSource = CancellationTokenSource()
            return suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { tokenSource.cancel() }
                @Suppress("MissingPermission") // checked above
                client
                    .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, tokenSource.token)
                    .addOnSuccessListener { location ->
                        continuation.resume(
                            if (location == null) {
                                CurrentLocation.Unavailable
                            } else {
                                CurrentLocation.Found(GeoPoint(location.latitude, location.longitude))
                            },
                        )
                    }.addOnFailureListener { continuation.resume(CurrentLocation.Unavailable) }
            }
        }
    }
