package com.ladygo.customer.booking.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.ladygo.customer.R
import com.ladygo.customer.api.generated.models.BookingDetailResponse
import com.ladygo.customer.booking.CancelPreview
import com.ladygo.customer.booking.ExternalIntents
import com.ladygo.customer.booking.GeoPoint
import com.ladygo.customer.booking.TrackBookingViewModel
import com.ladygo.customer.booking.TrackStage
import com.ladygo.customer.booking.TrackUiState
import com.ladygo.customer.ui.components.BannerKind
import com.ladygo.customer.ui.components.EmergencyButton
import com.ladygo.customer.ui.components.FareCard
import com.ladygo.customer.ui.components.InfoBanner
import com.ladygo.customer.ui.components.LadyGoScreen
import com.ladygo.customer.ui.components.LoadingState
import com.ladygo.customer.ui.components.Money
import com.ladygo.customer.ui.components.PersonVehicleCard
import com.ladygo.customer.ui.components.PinDisplay
import com.ladygo.customer.ui.components.PrimaryButton
import com.ladygo.customer.ui.components.QuietButton
import com.ladygo.customer.ui.components.RouteCard
import com.ladygo.customer.ui.components.StateIllustration
import com.ladygo.customer.ui.components.StatusChip
import com.ladygo.customer.ui.theme.LadyGoTheme
import kotlinx.coroutines.delay

private const val POLL_INTERVAL_MILLIS = 5_000L
private const val TRACK_MAP_HEIGHT_DP = 260

@Composable
fun TrackBookingScreen(
    onDone: () -> Unit,
    viewModel: TrackBookingViewModel = hiltViewModel(),
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    // Poll only while the screen is visible. Live push (STOMP) replaces this in the next slice.
    LaunchedEffect(lifecycleOwner, viewModel) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Stops once the ride is over (cancelled / completed): nothing left to learn from the server.
            while (viewModel.uiState.value.stage
                    ?.isLive != false
            ) {
                viewModel.refresh()
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }
    LaunchedEffect(state.gone) { if (state.gone) onDone() }

    TrackBookingContent(
        state = state,
        actions =
            TrackActions(
                onCancelRequested = viewModel::onCancelRequested,
                onCancelDismissed = viewModel::onCancelDismissed,
                onCancel = { reason -> viewModel.onCancel(reason) },
                onCancelUnsafe = { reason -> viewModel.onCancel(reason, unsafe = true) },
                onCall112 = { ExternalIntents.dialEmergency(context) },
                onDone = onDone,
            ),
        map = { st -> LiveTrackMap(st) },
        rating = { booking -> RateDriverCard(booking) },
    )
}

/** The real live map for the current stage: the pickup while the driver is coming, the destination during the trip. */
@Composable
private fun LiveTrackMap(state: TrackUiState) {
    val booking = state.booking ?: return
    val comingForRider = state.stage == TrackStage.DRIVER_COMING
    val latitude = if (comingForRider) booking.pickupLatitude else booking.destinationLatitude
    val longitude = if (comingForRider) booking.pickupLongitude else booking.destinationLongitude
    if (latitude != null && longitude != null) {
        TrackMap(
            stop = GeoPoint(latitude, longitude),
            stopIsPickup = comingForRider,
            driver = state.driver,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
fun TrackBookingContent(
    state: TrackUiState,
    actions: TrackActions,
    /** The live map. A slot so screen tests never need Play services; the real screen passes [TrackMap]. */
    map: @Composable (TrackUiState) -> Unit = {},
    /** The "rate your driver" card. A slot so screen tests need no view model; the real screen passes [RateDriverCard]. */
    rating: @Composable (BookingDetailResponse) -> Unit = {},
) {
    // The ride PIN is shown here: no screenshots / recordings while it is on screen.
    SecureWindowEffect(enabled = state.stage == TrackStage.DRIVER_COMING)
    val stage = state.stage
    val booking = state.booking
    if (stage == null || booking == null) {
        LadyGoScreen {
            LoadingState(message = stringResource(R.string.track_loading))
            state.error?.let { InfoBanner(text = it, kind = BannerKind.ERROR) }
        }
        return
    }
    LadyGoScreen(
        title = stageTitle(stage),
        bottomBar = {
            if (stage.showsEmergencyCall) {
                EmergencyButton(
                    text = stringResource(R.string.track_call_112_action),
                    onClick = actions.onCall112,
                    icon = Icons.Filled.Phone,
                )
            }
            if (stage.canCancel) {
                QuietButton(
                    text = stringResource(R.string.track_cancel_action),
                    onClick = actions.onCancelRequested,
                    enabled = !state.isCancelling,
                )
            }
            if (!stage.isLive) PrimaryButton(text = stringResource(R.string.track_done_action), onClick = actions.onDone)
        },
    ) {
        state.error?.let { InfoBanner(text = it, kind = BannerKind.ERROR, modifier = Modifier.testTag("track_error")) }
        StageHeader(stage)
        if (stage == TrackStage.DRIVER_COMING || stage == TrackStage.IN_TRIP) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(TRACK_MAP_HEIGHT_DP.dp)
                        .clip(MaterialTheme.shapes.large)
                        .testTag("track_map"),
            ) {
                map(state)
            }
        }
        if (stage == TrackStage.CANCELLED) CancelFeeNote(state.cancelFee, state.cancelFeeCurrency)
        StageBody(stage, booking)
        if (stage == TrackStage.PAYMENT || stage == TrackStage.FINISHED) rating(booking)
    }
    state.cancelPreview?.let { preview ->
        CancelDialog(
            preview = preview,
            canReportUnsafe = booking.driver != null,
            actions = actions,
        )
    }
}

/** After a cancel: says plainly whether she was charged, when we told her the fee beforehand. */
@Composable
private fun CancelFeeNote(
    fee: Long?,
    currency: String?,
) {
    when {
        fee == null -> Unit
        fee > 0 ->
            InfoBanner(
                text = stringResource(R.string.track_cancelled_fee_charged, Money.format(fee, currency)),
                kind = BannerKind.INFO,
                modifier = Modifier.testTag("cancel_fee_note"),
            )
        else ->
            InfoBanner(
                text = stringResource(R.string.track_cancelled_no_fee),
                kind = BannerKind.SUCCESS,
                modifier = Modifier.testTag("cancel_fee_note"),
            )
    }
}

@Composable
private fun StageHeader(stage: TrackStage) {
    when (stage) {
        TrackStage.SEARCHING ->
            StateIllustration(
                icon = Icons.Filled.Search,
                title = stringResource(R.string.track_headline_searching),
                body = stringResource(R.string.track_note_searching),
            )
        TrackStage.DRIVER_COMING ->
            StatusChip(
                text = stringResource(R.string.track_chip_coming),
                kind = BannerKind.SUCCESS,
                icon = Icons.Filled.DirectionsCar,
            )
        TrackStage.IN_TRIP ->
            StatusChip(
                text = stringResource(R.string.track_chip_in_trip),
                kind = BannerKind.INFO,
                icon = Icons.Filled.Shield,
            )
        TrackStage.PAYMENT ->
            StatusChip(
                text = stringResource(R.string.track_chip_arrived),
                kind = BannerKind.SUCCESS,
                icon = Icons.Filled.CheckCircle,
            )
        TrackStage.FINISHED ->
            StateIllustration(
                icon = Icons.Filled.Favorite,
                title = stringResource(R.string.track_headline_finished),
                body = stringResource(R.string.track_note_finished),
                tint = LadyGoTheme.extra.success,
                container = LadyGoTheme.extra.successContainer,
            )
        TrackStage.CANCELLED ->
            StateIllustration(
                icon = Icons.Filled.Cancel,
                title = stringResource(R.string.track_headline_cancelled),
                body = stringResource(R.string.track_note_cancelled),
            )
    }
}

@Composable
private fun StageBody(
    stage: TrackStage,
    booking: BookingDetailResponse,
) {
    if (stage == TrackStage.DRIVER_COMING) {
        val driver = booking.driver
        if (driver != null) {
            PersonVehicleCard(
                name = driver.name,
                verifiedLabel = stringResource(R.string.track_verified_driver),
                vehicle = listOfNotNull(driver.vehicleColor, driver.vehicleMake, driver.vehicleModel).joinToString(" "),
                plate = driver.vehicleRegistration,
            )
        }
        booking.ridePin?.let { pin ->
            PinDisplay(
                pin = pin,
                label = stringResource(R.string.track_pin_label),
                hint = stringResource(R.string.track_pin_hint),
                modifier = Modifier.testTag("track_pin"),
            )
        }
    }
    RouteCard(
        pickupLabel = stringResource(R.string.book_pickup_label),
        pickup = booking.pickupAddress,
        destinationLabel = stringResource(R.string.book_destination_label),
        destination = booking.destinationAddress,
    )
    val fare = booking.finalFare ?: booking.estimatedFare
    if (fare != null && stage != TrackStage.CANCELLED && stage != TrackStage.SEARCHING) {
        val cash = booking.paymentMethod == BookingDetailResponse.PaymentMethod.CASH
        FareCard(
            label = stringResource(R.string.book_fare_label),
            amount = Money.format(fare, booking.currency),
            caption =
                if (stage == TrackStage.PAYMENT &&
                    cash
                ) {
                    stringResource(R.string.track_note_pay_cash)
                } else {
                    stringResource(R.string.book_payment_cash)
                },
        )
    }
}

@Composable
private fun stageTitle(stage: TrackStage): String =
    stringResource(
        when (stage) {
            TrackStage.SEARCHING -> R.string.track_title_searching
            TrackStage.DRIVER_COMING -> R.string.track_title_driver_coming
            TrackStage.IN_TRIP -> R.string.track_title_in_trip
            TrackStage.PAYMENT -> R.string.track_title_payment
            TrackStage.FINISHED -> R.string.track_title_finished
            TrackStage.CANCELLED -> R.string.track_title_cancelled
        },
    )

private val CANCEL_REASONS =
    listOf(
        R.string.track_cancel_reason_changed_mind,
        R.string.track_cancel_reason_too_slow,
        R.string.track_cancel_reason_mistake,
        R.string.track_cancel_reason_other_ride,
        R.string.track_cancel_reason_other,
    )

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CancelDialog(
    preview: CancelPreview,
    canReportUnsafe: Boolean,
    actions: TrackActions,
) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var details by rememberSaveable { mutableStateOf("") }
    val isOther = CANCEL_REASONS[selected] == R.string.track_cancel_reason_other
    val chosenLabel = stringResource(CANCEL_REASONS[selected])
    val reason = if (isOther && details.isNotBlank()) details else chosenLabel
    AlertDialog(
        onDismissRequest = actions.onCancelDismissed,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(stringResource(R.string.track_cancel_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(feeText(preview), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("cancel_fee"))
                Text(stringResource(R.string.track_cancel_reason_label), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CANCEL_REASONS.forEachIndexed { index, label ->
                        FilterChip(
                            selected = selected == index,
                            onClick = { selected = index },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
                if (isOther) {
                    OutlinedTextField(
                        value = details,
                        onValueChange = { details = it.take(MAX_REASON_LENGTH) },
                        label = { Text(stringResource(R.string.track_cancel_details_label)) },
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().testTag("cancel_reason"),
                    )
                }
                if (canReportUnsafe) {
                    TextButton(
                        onClick = { actions.onCancelUnsafe("") },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.testTag("cancel_unsafe"),
                    ) { Text(stringResource(R.string.track_cancel_unsafe)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { actions.onCancel(reason) }, enabled = preview != CancelPreview.Loading) {
                Text(stringResource(R.string.track_cancel_confirm))
            }
        },
        dismissButton = { TextButton(onClick = actions.onCancelDismissed) { Text(stringResource(R.string.track_cancel_keep)) } },
    )
}

@Composable
private fun feeText(preview: CancelPreview): String =
    when (preview) {
        CancelPreview.Loading -> stringResource(R.string.track_cancel_fee_loading)
        CancelPreview.Unavailable -> stringResource(R.string.track_cancel_fee_note)
        is CancelPreview.Ready ->
            when {
                preview.fee > 0 -> stringResource(R.string.track_cancel_fee_applies, Money.format(preview.fee, preview.currency))
                preview.freeUntilMillis != null -> {
                    val minutes = ((preview.freeUntilMillis - System.currentTimeMillis()) / MILLIS_PER_MINUTE).coerceAtLeast(0) + 1
                    stringResource(R.string.track_cancel_fee_free_window, minutes.toInt())
                }
                else -> stringResource(R.string.track_cancel_fee_free)
            }
    }

private const val MAX_REASON_LENGTH = 200
private const val MILLIS_PER_MINUTE = 60_000L
