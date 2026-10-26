package com.ladygo.customer.booking.ui

import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
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
import androidx.compose.ui.input.pointer.RequestDisallowInterceptTouchEvent
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import com.ladygo.customer.R
import com.ladygo.customer.booking.GeoPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * The map the rider moves under a fixed centre pin (docs/product/map-design.md section 3). The address is
 * only looked up once the map has been still for [SETTLE_MILLIS], so dragging never fires a lookup per frame
 * (the "pin is the source of truth, geocode when it settles" cost rule, ADR-011).
 *
 * [start] is where the map opens; [markerAt] draws a fixed marker, e.g. the pickup while she picks a destination.
 * Key the composable on whatever should re-centre it (pickup edit mode), since [start] is read once.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MapPinPicker(
    start: GeoPoint,
    pinDescription: String,
    onSettled: (GeoPoint) -> Unit,
    modifier: Modifier = Modifier,
    markerAt: GeoPoint? = null,
) {
    val cameraPositionState =
        rememberCameraPositionState {
            position = CameraPosition.fromLatLngZoom(LatLng(start.latitude, start.longitude), INITIAL_ZOOM)
        }
    val latestOnSettled by rememberUpdatedState(onSettled)
    LaunchedEffect(cameraPositionState) {
        snapshotFlow { cameraPositionState.isMoving }.collectLatest { moving ->
            if (!moving) {
                delay(SETTLE_MILLIS)
                val target = cameraPositionState.position.target
                latestOnSettled(GeoPoint(target.latitude, target.longitude))
            }
        }
    }
    val mapDescription = stringResource(R.string.book_map_description)
    // The booking page scrolls vertically; without this a vertical drag on the map scrolls the page instead of
    // moving the map (found on the emulator). While a finger is down on the map, the parent must not take over.
    val disallowParentScroll = remember { RequestDisallowInterceptTouchEvent() }
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
            uiSettings =
                MapUiSettings(
                    zoomControlsEnabled = false,
                    myLocationButtonEnabled = false,
                    mapToolbarEnabled = false,
                    rotationGesturesEnabled = false,
                    tiltGesturesEnabled = false,
                ),
        ) {
            markerAt?.let { Marker(state = MarkerState(LatLng(it.latitude, it.longitude))) }
        }
        // The pin is a fixed overlay at the map's centre; its tip, not its middle, marks the point.
        Icon(
            imageVector = Icons.Filled.Place,
            contentDescription = pinDescription,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.Center).offset(y = (-PIN_SIZE_DP / 2).dp).size(PIN_SIZE_DP.dp),
        )
    }
}

private const val INITIAL_ZOOM = 16f
private const val SETTLE_MILLIS = 400L
private const val PIN_SIZE_DP = 44
