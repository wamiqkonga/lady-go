package com.ladygo.customer.booking.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import com.ladygo.customer.R
import com.ladygo.customer.booking.GeoPoint

/**
 * The live map on the track screen: her pickup (while the driver is on the way) or destination (during the trip), and
 * the driver's car moving towards it. The car glides between the positions the server reports every few seconds
 * instead of jumping. Read-only, like the fare map: the page scrolls, and a map taking drags inside it fights the page.
 * With no live position (the driver has not reported yet) only the stop is shown.
 */
@Composable
fun TrackMap(
    stop: GeoPoint,
    stopIsPickup: Boolean,
    driver: GeoPoint?,
    modifier: Modifier = Modifier,
) {
    val stopLatLng = remember(stop) { LatLng(stop.latitude, stop.longitude) }
    val car = rememberGlidingPosition(driver)
    val cameraPositionState = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(stopLatLng, STOP_ZOOM) }
    var loaded by remember { mutableStateOf(false) }
    // Re-frame around the car and its next stop whenever the server reports a new position (not on every glide frame).
    LaunchedEffect(loaded, driver, stopLatLng) {
        if (!loaded) return@LaunchedEffect
        val update =
            if (driver != null) {
                CameraUpdateFactory.newLatLngBounds(
                    LatLngBounds
                        .builder()
                        .include(stopLatLng)
                        .include(LatLng(driver.latitude, driver.longitude))
                        .build(),
                    FRAME_PADDING_PX,
                )
            } else {
                CameraUpdateFactory.newLatLngZoom(stopLatLng, STOP_ZOOM)
            }
        cameraPositionState.animate(update)
    }
    val description = stringResource(R.string.track_map_description)
    Box(modifier = modifier.semantics { contentDescription = description }) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            uiSettings =
                MapUiSettings(
                    compassEnabled = false,
                    mapToolbarEnabled = false,
                    myLocationButtonEnabled = false,
                    zoomControlsEnabled = false,
                    scrollGesturesEnabled = false,
                    zoomGesturesEnabled = false,
                    rotationGesturesEnabled = false,
                    tiltGesturesEnabled = false,
                ),
            onMapLoaded = { loaded = true },
        ) {
            Marker(
                state = MarkerState(stopLatLng),
                icon =
                    BitmapDescriptorFactory.defaultMarker(
                        if (stopIsPickup) BitmapDescriptorFactory.HUE_GREEN else BitmapDescriptorFactory.HUE_ROSE,
                    ),
            )
            car?.let {
                Marker(
                    state = MarkerState(it),
                    icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE),
                    zIndex = 1f,
                )
            }
        }
    }
}

/**
 * The position to draw: eases from where the car was drawn to each newly reported position. The first position (and a
 * car that reappears after being dropped) is shown where it is, with no glide from nowhere.
 */
@Composable
private fun rememberGlidingPosition(target: GeoPoint?): LatLng? {
    var from by remember { mutableStateOf<LatLng?>(null) }
    var to by remember { mutableStateOf<LatLng?>(null) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(target) {
        if (target == null) {
            from = null
            to = null
            return@LaunchedEffect
        }
        val next = LatLng(target.latitude, target.longitude)
        val current = to?.let { lerp(from ?: it, it, progress.value) }
        from = current ?: next
        to = next
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = GLIDE_MILLIS))
    }
    val end = to ?: return null
    return lerp(from ?: end, end, progress.value)
}

private fun lerp(
    a: LatLng,
    b: LatLng,
    t: Float,
): LatLng = LatLng(a.latitude + (b.latitude - a.latitude) * t, a.longitude + (b.longitude - a.longitude) * t)

private const val STOP_ZOOM = 15f
private const val FRAME_PADDING_PX = 120
private const val GLIDE_MILLIS = 4_000
