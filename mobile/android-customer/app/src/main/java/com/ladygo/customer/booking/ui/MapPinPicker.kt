package com.ladygo.customer.booking.ui

import android.annotation.SuppressLint
import android.view.MotionEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.RequestDisallowInterceptTouchEvent
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberCameraPositionState
import com.ladygo.customer.R
import com.ladygo.customer.booking.GeoPoint
import com.ladygo.customer.booking.MapFocus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * The map the rider moves under a fixed centre pin (docs/product/map-design.md section 3). The address is
 * only looked up once the map has been still for [SETTLE_MILLIS], so dragging never fires a lookup per frame
 * (the "pin is the source of truth, geocode when it settles" cost rule, ADR-011). [onMoveStart] fires when a
 * drag begins so the caller can drop the pin it was holding; nothing is looked up until the rider has actually
 * moved the map, so merely opening it does not spend a paid lookup or pre-confirm the starting point.
 *
 * [start] is where the map opens; [markerAt] draws the pickup (a green dot) while she picks a destination. [focus]
 * moves the map to a search result or to "my location" - each new [MapFocus.seq] is one smooth move, after which the
 * map settles and the pin's address is looked up like after any drag. The blue dot shows where she is once location
 * is allowed. Key the composable on whatever should re-centre it (pickup edit mode), since [start] is read once.
 */
@OptIn(ExperimentalComposeUiApi::class)
@SuppressLint("MissingPermission") // the blue dot is only switched on when [showMyLocation] says the permission is held
@Composable
fun MapPinPicker(
    start: GeoPoint,
    pinDescription: String,
    onMoveStart: () -> Unit,
    onSettled: (GeoPoint) -> Unit,
    modifier: Modifier = Modifier,
    markerAt: GeoPoint? = null,
    focus: MapFocus? = null,
    showMyLocation: Boolean = false,
    onRecenter: (() -> Unit)? = null,
) {
    val cameraPositionState =
        rememberCameraPositionState {
            position = CameraPosition.fromLatLngZoom(LatLng(start.latitude, start.longitude), INITIAL_ZOOM)
        }
    val latestOnSettled by rememberUpdatedState(onSettled)
    val latestOnMoveStart by rememberUpdatedState(onMoveStart)
    LaunchedEffect(cameraPositionState) {
        var hasMoved = false
        snapshotFlow { cameraPositionState.isMoving }.collectLatest { moving ->
            if (moving) {
                hasMoved = true
                latestOnMoveStart()
            } else if (hasMoved) {
                delay(SETTLE_MILLIS)
                val target = cameraPositionState.position.target
                latestOnSettled(GeoPoint(target.latitude, target.longitude))
            }
        }
    }
    // A new focus is one smooth move; the map then settles and the address is looked up as usual.
    LaunchedEffect(focus?.seq) {
        focus?.let {
            cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(LatLng(it.point.latitude, it.point.longitude), FOCUS_ZOOM))
        }
    }
    val mapDescription = stringResource(R.string.book_map_description)
    // The booking page scrolls vertically; without this a vertical drag on the map scrolls the page instead of
    // moving the map (found on the emulator). While a finger is down on the map, the parent must not take over.
    val disallowParentScroll = remember { RequestDisallowInterceptTouchEvent() }
    val context = LocalContext.current
    val pinImage = remember { MapIcons.drawPin(context).asImageBitmap() }
    val pickupIcon = MapIcons.pickup()
    Box(
        modifier =
            modifier
                .semantics { contentDescription = mapDescription }
                .pointerInteropFilter(requestDisallowInterceptTouchEvent = disallowParentScroll) { event ->
                    disallowParentScroll(event.action != MotionEvent.ACTION_UP && event.action != MotionEvent.ACTION_CANCEL)
                    false
                },
    ) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(isMyLocationEnabled = showMyLocation),
            uiSettings =
                MapUiSettings(
                    zoomControlsEnabled = false,
                    myLocationButtonEnabled = false,
                    mapToolbarEnabled = false,
                    rotationGesturesEnabled = false,
                    tiltGesturesEnabled = false,
                ),
        ) {
            markerAt?.let {
                Marker(
                    state = rememberMarkerAt(LatLng(it.latitude, it.longitude)),
                    icon = pickupIcon,
                    anchor =
                        androidx.compose.ui.geometry
                            .Offset(0.5f, 0.5f),
                )
            }
        }
        CenterPin(pinImage, pinDescription)
        if (onRecenter != null) RecenterButton(onRecenter, Modifier.align(Alignment.BottomEnd))
    }
}

/** The pin is a fixed overlay at the map's centre; its tip, not its middle, marks the point. */
@Composable
private fun BoxScope.CenterPin(
    image: androidx.compose.ui.graphics.ImageBitmap,
    description: String,
) {
    Image(
        bitmap = image,
        contentDescription = description,
        modifier = Modifier.align(Alignment.Center).offset(y = (-PIN_OFFSET_DP).dp).size(PIN_W_DP.dp, PIN_H_DP.dp),
    )
}

@Composable
private fun RecenterButton(
    onClick: () -> Unit,
    modifier: Modifier,
) {
    FloatingActionButton(
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(12.dp).size(48.dp).testTag("book_recenter"),
    ) {
        Icon(Icons.Filled.MyLocation, contentDescription = stringResource(R.string.book_recenter_description))
    }
}

private const val INITIAL_ZOOM = 16f
private const val FOCUS_ZOOM = 17f
private const val SETTLE_MILLIS = 400L
private const val PIN_W_DP = 38
private const val PIN_H_DP = 50
private const val PIN_OFFSET_DP = PIN_H_DP / 2
