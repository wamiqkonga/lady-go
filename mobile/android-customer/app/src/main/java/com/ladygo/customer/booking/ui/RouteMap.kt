package com.ladygo.customer.booking.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.ladygo.customer.R
import com.ladygo.customer.booking.GeoPoint

/**
 * A read-only map of the trip on the fare screen: the route, a green pickup marker and a rose destination marker,
 * framed to fit. It is deliberately not interactive - the fare page scrolls, and a map that takes drags inside a
 * scrolling page fights the page. With no route from the server (the maps provider was unreachable) the two points
 * are joined by a dashed straight line, so the rider still sees where the ride goes.
 */
@Composable
fun RouteMap(
    pickup: GeoPoint,
    destination: GeoPoint,
    route: List<GeoPoint>,
    modifier: Modifier = Modifier,
) {
    val line =
        remember(pickup, destination, route) { route.ifEmpty { listOf(pickup, destination) }.map { LatLng(it.latitude, it.longitude) } }
    val bounds =
        remember(line, pickup, destination) {
            LatLngBounds
                .builder()
                .apply {
                    line.forEach(::include)
                    include(LatLng(pickup.latitude, pickup.longitude))
                    include(LatLng(destination.latitude, destination.longitude))
                }.build()
        }
    val cameraPositionState =
        rememberCameraPositionState {
            position = CameraPosition.fromLatLngZoom(bounds.center, FALLBACK_ZOOM)
        }
    val description = stringResource(R.string.book_route_map_description)
    val routeColor = MaterialTheme.colorScheme.primary
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
            // Framing needs the map's size, which exists once it has loaded.
            onMapLoaded = { cameraPositionState.move(CameraUpdateFactory.newLatLngBounds(bounds, FRAME_PADDING_PX)) },
        ) {
            Polyline(
                points = line,
                color = routeColor,
                width = ROUTE_WIDTH,
                pattern = if (route.isEmpty()) listOf(Dash(DASH_LENGTH), Gap(DASH_LENGTH)) else null,
            )
            Marker(
                state = MarkerState(LatLng(pickup.latitude, pickup.longitude)),
                icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN),
            )
            Marker(
                state = MarkerState(LatLng(destination.latitude, destination.longitude)),
                icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ROSE),
            )
        }
    }
}

private const val FALLBACK_ZOOM = 14f
private const val FRAME_PADDING_PX = 96
private const val ROUTE_WIDTH = 10f
private const val DASH_LENGTH = 24f
