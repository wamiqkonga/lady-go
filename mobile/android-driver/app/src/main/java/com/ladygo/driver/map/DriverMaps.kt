package com.ladygo.driver.map

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.ladygo.driver.R
import com.ladygo.driver.ride.RideRouteViewModel
import java.util.UUID

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
 * The ride map: the pickup (green dot) and destination (rose pin), the road to where she is going next, and her own pink
 * scooter gliding along it, framed around her and the next stop. The road comes from the server (and is refreshed as
 * she moves); with none, a dashed straight line stands in. Turn-by-turn stays with the "Navigate" hand-off to Google Maps.
 */
@Composable
fun RideMap(
    pickup: LatLng?,
    destination: LatLng?,
    focus: RideFocus,
    modifier: Modifier = Modifier,
    bookingId: UUID? = null,
    routeViewModel: RideRouteViewModel? = if (bookingId != null) hiltViewModel() else null,
) {
    val me by rememberDevicePosition()
    val target = if (focus == RideFocus.PICKUP) pickup else destination
    val cameraPositionState =
        rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(target ?: pickup ?: DEFAULT_CENTER, RIDE_ZOOM) }
    var loaded by remember { mutableStateOf(false) }
    FrameAround(cameraPositionState, loaded, me, target)
    val road = rememberRoad(routeViewModel, bookingId, me, focus)
    val glide = rememberGlidingPosition(me)
    val pickupIcon = MapIcons.pickup()
    val destinationIcon = MapIcons.destination()
    val scooter = MapIcons.scooter(glide?.facingRight ?: true)
    val roadColor = MaterialTheme.colorScheme.primary
    val description = stringResource(R.string.map_ride_description)
    Box(modifier = modifier.semantics { contentDescription = description }) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            uiSettings = ReadOnlyMap,
            onMapLoaded = { loaded = true },
        ) {
            val here = glide?.position
            if (road.size >= 2) {
                Polyline(points = road, color = Color.White, width = ROAD_OUTLINE_WIDTH)
                Polyline(points = road, color = roadColor, width = ROAD_WIDTH, zIndex = 0.1f)
            } else if (here != null && target != null) {
                Polyline(points = listOf(here, target), color = roadColor, width = ROAD_WIDTH, pattern = listOf(Dash(DASH), Gap(DASH)))
            }
            pickup?.let { Marker(state = MarkerState(it), icon = pickupIcon, anchor = Offset(0.5f, 0.5f)) }
            destination?.let { Marker(state = MarkerState(it), icon = destinationIcon, anchor = Offset(0.5f, 1f)) }
            here?.let { Marker(state = MarkerState(it), icon = scooter, anchor = Offset(0.5f, 0.85f), zIndex = 1f) }
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
    val glide = rememberGlidingPosition(me)
    val scooter = MapIcons.scooter(glide?.facingRight ?: true)
    val description = stringResource(R.string.map_home_description)
    Box(modifier = modifier.semantics { contentDescription = description }) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            uiSettings = ReadOnlyMap,
            onMapLoaded = { loaded = true },
        ) {
            glide?.let { Marker(state = MarkerState(it.position), icon = scooter, anchor = Offset(0.5f, 0.85f)) }
        }
    }
}

/**
 * The road to her next stop. Fetched when the stop changes and again once she has moved a good way or a while has
 * passed (each fetch is a billed maps call, so not on every position fix); a failed or empty answer leaves the old road.
 */
@Composable
private fun rememberRoad(
    viewModel: RideRouteViewModel?,
    bookingId: UUID?,
    me: LatLng?,
    focus: RideFocus,
): List<LatLng> {
    var road by remember(focus) { mutableStateOf<List<LatLng>>(emptyList()) }
    var fetchedFrom by remember(focus) { mutableStateOf<LatLng?>(null) }
    var fetchedAt by remember(focus) { mutableLongStateOf(0L) }
    LaunchedEffect(viewModel, bookingId, me, focus) {
        val here = me ?: return@LaunchedEffect
        if (viewModel == null || bookingId == null) return@LaunchedEffect
        val now = System.currentTimeMillis()
        if (!roadIsDue(fetchedFrom, here, now - fetchedAt)) return@LaunchedEffect
        fetchedFrom = here
        fetchedAt = now
        val fresh = viewModel.leg(bookingId, here, focus == RideFocus.DESTINATION)
        if (fresh.isNotEmpty()) road = fresh
    }
    return road
}

/** Never fetched yet; or she has moved a good way and a little while has passed; or the road is simply old. */
private fun roadIsDue(
    lastFrom: LatLng?,
    here: LatLng,
    ageMillis: Long,
): Boolean {
    if (lastFrom == null || ageMillis >= REFETCH_MAX_MILLIS) return true
    return ageMillis > REFETCH_MIN_MILLIS && distanceMeters(lastFrom, here) > REFETCH_DISTANCE_METERS
}

private fun distanceMeters(
    a: LatLng,
    b: LatLng,
): Double {
    val metresPerDegree = 111_320.0
    val dLat = (a.latitude - b.latitude) * metresPerDegree
    val dLng = (a.longitude - b.longitude) * metresPerDegree * kotlin.math.cos(Math.toRadians(a.latitude))
    return kotlin.math.sqrt(dLat * dLat + dLng * dLng)
}

/** Where to draw the scooter now, and which way it is facing. */
private data class Glide(
    val position: LatLng,
    val facingRight: Boolean,
)

/** Eases the scooter from where it was drawn to each new position fix, at a steady pace; it faces east or west by where it last moved. */
@Composable
private fun rememberGlidingPosition(target: LatLng?): Glide? {
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
        val current = to?.let { lerp(from ?: it, it, progress.value) }
        if (current != null && target.longitude != current.longitude) facingRight = target.longitude > current.longitude
        from = current ?: target
        to = target
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
private const val ROAD_WIDTH = 12f
private const val ROAD_OUTLINE_WIDTH = 20f
private const val DASH = 24f
private const val GLIDE_MILLIS = 4_000
private const val REFETCH_DISTANCE_METERS = 150.0
private const val REFETCH_MIN_MILLIS = 20_000L
private const val REFETCH_MAX_MILLIS = 90_000L
