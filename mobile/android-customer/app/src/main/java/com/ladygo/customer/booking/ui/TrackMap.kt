package com.ladygo.customer.booking.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.ladygo.customer.R
import com.ladygo.customer.booking.GeoPoint

/**
 * The live map on the track screen: her pickup (while the driver is on the way) or destination (during the trip), the
 * road to it, and the driver's pink scooter riding along it. The scooter glides between the positions the server
 * reports every few seconds instead of jumping, and looks the way it is heading. Read-only, like the fare map: the
 * page scrolls, and a map taking drags inside it fights the page. With no live position (the driver has not reported
 * yet) only the stop is shown; with no road from the server, a dashed straight line stands in for it.
 */
@Composable
fun TrackMap(
    stop: GeoPoint,
    stopIsPickup: Boolean,
    driver: GeoPoint?,
    modifier: Modifier = Modifier,
    route: List<GeoPoint> = emptyList(),
) {
    val stopLatLng = remember(stop) { LatLng(stop.latitude, stop.longitude) }
    val glide = rememberGlidingPosition(driver)
    val cameraPositionState = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(stopLatLng, STOP_ZOOM) }
    var loaded by remember { mutableStateOf(false) }
    // Re-frame around the scooter and its next stop whenever the server reports a new position (not on every glide frame).
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
    val line = remember(route) { route.map { LatLng(it.latitude, it.longitude) } }
    val stopIcon = if (stopIsPickup) MapIcons.pickup() else MapIcons.destination()
    val scooter = MapIcons.scooter(glide?.facingRight ?: true)
    val routeColor = androidx.compose.material3.MaterialTheme.colorScheme.primary
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
            val here = glide?.position
            if (line.size >= 2) {
                Polyline(points = line, color = Color.White, width = ROUTE_OUTLINE_WIDTH, zIndex = 0f)
                Polyline(points = line, color = routeColor, width = ROUTE_WIDTH, zIndex = 0.1f)
            } else if (here != null) {
                Polyline(
                    points = listOf(here, stopLatLng),
                    color = routeColor,
                    width = ROUTE_WIDTH,
                    pattern = listOf(Dash(DASH), Gap(DASH)),
                )
            }
            Marker(
                state = rememberMarkerAt(stopLatLng),
                icon = stopIcon,
                anchor = if (stopIsPickup) Offset(0.5f, 0.5f) else Offset(0.5f, 1f),
            )
            here?.let {
                Marker(state = rememberMarkerAt(it), icon = scooter, anchor = Offset(0.5f, 0.85f), zIndex = 1f)
            }
        }
    }
}

/** Where to draw the scooter now, and which way it is facing. */
private data class Glide(
    val position: LatLng,
    val facingRight: Boolean,
)

/**
 * The position to draw: eases from where the scooter was drawn to each newly reported position, at a steady pace (it
 * keeps moving between reports instead of speeding up and stopping). The first position (and one that reappears after
 * being dropped) is shown where it is, with no glide from nowhere. It faces east or west by where it last moved.
 */
@Composable
private fun rememberGlidingPosition(target: GeoPoint?): Glide? {
    var from by remember { mutableStateOf<LatLng?>(null) }
    var to by remember { mutableStateOf<LatLng?>(null) }
    var facingRight by remember { mutableStateOf(true) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(target) {
        if (target == null) {
            from = null
            to = null
            return@LaunchedEffect
        }
        val next = LatLng(target.latitude, target.longitude)
        val current = to?.let { lerp(from ?: it, it, progress.value) }
        if (current != null && next.longitude != current.longitude) facingRight = next.longitude > current.longitude
        from = current ?: next
        to = next
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = GLIDE_MILLIS, easing = LinearEasing))
    }
    val end = to ?: return null
    return Glide(lerp(from ?: end, end, progress.value), facingRight)
}

private fun lerp(
    a: LatLng,
    b: LatLng,
    t: Float,
): LatLng = LatLng(a.latitude + (b.latitude - a.latitude) * t, a.longitude + (b.longitude - a.longitude) * t)

private const val STOP_ZOOM = 15f
private const val FRAME_PADDING_PX = 140
private const val GLIDE_MILLIS = 5_000
private const val ROUTE_WIDTH = 12f
private const val ROUTE_OUTLINE_WIDTH = 20f
private const val DASH = 24f
