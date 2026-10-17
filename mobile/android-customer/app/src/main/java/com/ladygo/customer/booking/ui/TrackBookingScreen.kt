package com.ladygo.customer.booking.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.ladygo.customer.booking.ExternalIntents
import com.ladygo.customer.booking.TrackBookingViewModel
import com.ladygo.customer.booking.TrackStage
import com.ladygo.customer.booking.TrackUiState
import kotlinx.coroutines.delay

private const val POLL_INTERVAL_MILLIS = 5_000L

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
                onCancel = viewModel::onCancel,
                onCall112 = { ExternalIntents.dialEmergency(context) },
                onDone = onDone,
            ),
    )
}

@Composable
fun TrackBookingContent(
    state: TrackUiState,
    actions: TrackActions,
) {
    // The ride PIN is shown here: no screenshots / recordings while it is on screen.
    SecureWindowEffect(enabled = state.stage == TrackStage.DRIVER_COMING)
    var showCancel by rememberSaveable { mutableStateOf(false) }
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding).padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val stage = state.stage
            val booking = state.booking
            if (stage == null || booking == null) {
                CircularProgressIndicator()
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                return@Column
            }
            Text(stageTitle(stage), style = MaterialTheme.typography.headlineMedium)
            RouteSummary(booking)
            if (stage == TrackStage.DRIVER_COMING) DriverCard(booking)
            if (stage == TrackStage.SEARCHING) CircularProgressIndicator()
            StageNote(stage, booking)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("track_error")) }
            if (stage.showsEmergencyCall) {
                OutlinedButton(onClick = actions.onCall112, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.track_call_112_action))
                }
            }
            if (stage.canCancel) {
                OutlinedButton(onClick = { showCancel = true }, enabled = !state.isCancelling, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.track_cancel_action))
                }
            }
            if (!stage.isLive) {
                Button(onClick = actions.onDone, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.track_done_action))
                }
            }
        }
    }
    if (showCancel) {
        CancelDialog(
            onConfirm = { reason ->
                showCancel = false
                actions.onCancel(reason)
            },
            onDismiss = { showCancel = false },
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

@Composable
private fun RouteSummary(booking: BookingDetailResponse) {
    booking.pickupAddress?.let { Text("${stringResource(R.string.book_pickup_label)}: $it") }
    booking.destinationAddress?.let { Text("${stringResource(R.string.book_destination_label)}: $it") }
    val fare = booking.finalFare ?: booking.estimatedFare
    if (fare != null) Text("${stringResource(R.string.book_fare_label)}: ${booking.currency.orEmpty()} $fare".replace("  ", " "))
}

@Composable
private fun DriverCard(booking: BookingDetailResponse) {
    val driver = booking.driver
    if (driver != null) {
        Text(driver.name.orEmpty(), style = MaterialTheme.typography.titleMedium)
        val vehicle = listOfNotNull(driver.vehicleColor, driver.vehicleMake, driver.vehicleModel).joinToString(" ")
        if (vehicle.isNotBlank()) Text(vehicle)
        driver.vehicleRegistration?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
    }
    booking.ridePin?.let { pin ->
        Text(stringResource(R.string.track_pin_label), style = MaterialTheme.typography.labelLarge)
        Text(pin, style = MaterialTheme.typography.displaySmall, modifier = Modifier.testTag("track_pin"))
        Text(stringResource(R.string.track_pin_hint))
    }
}

@Composable
private fun StageNote(
    stage: TrackStage,
    booking: BookingDetailResponse,
) {
    when (stage) {
        TrackStage.SEARCHING -> Text(stringResource(R.string.track_note_searching))
        TrackStage.PAYMENT ->
            if (booking.paymentMethod == BookingDetailResponse.PaymentMethod.CASH) Text(stringResource(R.string.track_note_pay_cash))
        TrackStage.FINISHED -> Text(stringResource(R.string.track_note_finished))
        TrackStage.CANCELLED -> Text(stringResource(R.string.track_note_cancelled))
        else -> Unit
    }
}

@Composable
private fun CancelDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var reason by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.track_cancel_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.track_cancel_fee_note))
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text(stringResource(R.string.track_cancel_reason_label)) },
                    modifier = Modifier.fillMaxWidth().testTag("cancel_reason"),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(reason) }) { Text(stringResource(R.string.track_cancel_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.track_cancel_keep)) } },
    )
}
