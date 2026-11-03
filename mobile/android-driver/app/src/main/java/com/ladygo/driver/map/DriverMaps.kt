package com.ladygo.driver.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
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
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import com.ladygo.driver.R

/** A fixed, read-only map. The driver screens scroll, so a map that took drags would fight the page. */
private val ReadOnlyMap =
    MapUiSettings(
        compassEnabled = false,
        mapToolbarEnabled = false,
        myLocationButtonEnabled = false,
        zoomControlsEnabled = false,
        scrollGesturesEnabled = false,
        zoomGesturesEnabled = false,
        rotationGesturesEnabled = false,
        tiltGesturesEnabled = false,
    )

/**
 * The area of a ride offer. The server only gives the approximate pickup before she accepts (the exact point stays
 * hidden), so this draws a soft circle for "about here", never a pin.
 */
@Composable
fun OfferMapPreview(
    approximatePickup: LatLng,
    modifier: Modifier = Modifier,
) {
    val cameraPositionState =
        rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(approximatePickup, OFFER_ZOOM) }
    val description = stringResource(R.string.map_offer_description)
    val tint = MaterialTheme.colorScheme.primary
    Box(modifier = modifier.semantics { contentDescription = description }) {
        GoogleMap(modifier = Modifier.fillMaxSize(), cameraPositionState = cameraPositionState, uiSettings = ReadOnlyMap) {
            Circle(
                center = approximatePickup,
                radius = OFFER_AREA_RADIUS_METERS,
                fillColor = tint.copy(alpha = AREA_FILL_ALPHA),
                strokeColor = tint,
                strokeWidth = AREA_STROKE_WIDTH,
            )
        }
    }
}

/**
 * The ride map: green pickup and rose destination markers and the driver's own position, framed around her and where
 * she is going next. Turn-by-turn stays with the "Navigate" hand-off to the Google Maps app.
 */
@Composable
fun RideMap(
    pickup: LatLng?,
    destination: LatLng?,
    focus: RideFocus,
    modifier: Modifier = Modifier,
) {
    val me by rememberDevicePosition()
    val target = if (focus == RideFocus.PICKUP) pickup else destination
    val cameraPositionState =
        rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(target ?: pickup ?: DEFAULT_CENTER, RIDE_ZOOM) }
    var loaded by remember { mutableStateOf(false) }
    FrameAround(cameraPositionState, loaded, me, target)
    val description = stringResource(R.string.map_ride_description)
    Box(modifier = modifier.semantics { contentDescription = description }) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            uiSettings = ReadOnlyMap,
            onMapLoaded = { loaded = true },
        ) {
            pickup?.let { Marker(state = MarkerState(it), icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)) }
            destination?.let {
                Marker(
                    state = MarkerState(it),
                    icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ROSE),
                )
            }
            me?.let { Marker(state = MarkerState(it), icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)) }
        }
    }
}

/** The driver's own position while she is online, so she can see the app knows where she is. */
@Composable
fun HomeMap(modifier: Modifier = Modifier) {
    val me by rememberDevicePosition()
    val cameraPositionState = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(DEFAULT_CENTER, HOME_ZOOM) }
    var loaded by remember { mutableStateOf(false) }
    FrameAround(cameraPositionState, loaded, me, null)
    val description = stringResource(R.string.map_home_description)
    Box(modifier = modifier.semantics { contentDescription = description }) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            uiSettings = ReadOnlyMap,
            onMapLoaded = { loaded = true },
        ) {
            me?.let { Marker(state = MarkerState(it), icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)) }
        }
    }
}

/** Keeps the camera on the driver (and her next stop, if any) as her position changes. */
@Composable
private fun FrameAround(
    cameraPositionState: CameraPositionState,
    loaded: Boolean,
    me: LatLng?,
    target: LatLng?,
) {
    LaunchedEffect(loaded, me, target) {
        if (!loaded) return@LaunchedEffect
        val update =
            when {
                me != null && target != null ->
                    CameraUpdateFactory.newLatLngBounds(
                        LatLngBounds
                            .builder()
                            .include(me)
                            .include(target)
                            .build(),
                        FRAME_PADDING_PX,
                    )
                me != null -> CameraUpdateFactory.newLatLngZoom(me, HOME_ZOOM)
                target != null -> CameraUpdateFactory.newLatLngZoom(target, RIDE_ZOOM)
                else -> return@LaunchedEffect
            }
        cameraPositionState.animate(update)
    }
}

private val DEFAULT_CENTER = LatLng(34.0837, 74.7973) // Srinagar, until her own position is known
private const val OFFER_ZOOM = 14f
private const val RIDE_ZOOM = 15f
private const val HOME_ZOOM = 16f
private const val FRAME_PADDING_PX = 120
private const val OFFER_AREA_RADIUS_METERS = 350.0
private const val AREA_FILL_ALPHA = 0.18f
private const val AREA_STROKE_WIDTH = 4f
