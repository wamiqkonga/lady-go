package com.ladygo.driver.ride.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ladygo.driver.R
import com.ladygo.driver.api.generated.models.BookingDetailResponse
import com.ladygo.driver.ride.ActiveRideUiState
import com.ladygo.driver.ride.ActiveRideViewModel
import com.ladygo.driver.ride.ExternalIntents
import com.ladygo.driver.ride.RideStage
import com.ladygo.driver.ride.SelfieState

@Composable
fun ActiveRideScreen(
    onFinished: () -> Unit,
    viewModel: ActiveRideViewModel = hiltViewModel(),
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    // The driver can only leave through "Back to home" once the ride is over; an ongoing ride has no
    // dismiss path (and system Back must not be one).
    BackHandler(enabled = true) {}

    val noMapsMessage = stringResource(R.string.ride_no_navigation_app)
    ActiveRideContent(
        state = state,
        actions =
            ActiveRideActions(
                onImArrived = viewModel::onImArrived,
                onStartRide = viewModel::onStartRide,
                onComplete = viewModel::onComplete,
                onEndEarly = viewModel::onEndEarly,
                onConfirmCash = viewModel::onConfirmCashCollected,
                onRetrySelfie = viewModel::onRetrySelfie,
                onNavigate = { address ->
                    if (!ExternalIntents.navigateTo(context, address)) {
                        Toast.makeText(context, noMapsMessage, Toast.LENGTH_SHORT).show()
                    }
                },
                onCall112 = { ExternalIntents.dialEmergency(context) },
                onDone = {
                    viewModel.onDone()
                    onFinished()
                },
            ),
    )
}

@Composable
fun ActiveRideContent(
    state: ActiveRideUiState,
    actions: ActiveRideActions,
) {
    val booking = state.booking ?: return
    val stage = state.stage ?: return

    // PIN entry and the rider photo are the two sensitive surfaces (design doc §4): no screenshots or
    // screen recording while they are on screen.
    SecureWindowEffect(enabled = stage == RideStage.ARRIVED)

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = stageTitle(stage), style = MaterialTheme.typography.headlineMedium)
            RideSummary(booking = booking)
            state.error?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("ride_error"),
                )
            }
            StageActions(state = state, stage = stage, booking = booking, actions = actions)
            if (stage.showsEmergencyCall) {
                OutlinedButton(onClick = actions.onCall112, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.ride_call_112_action))
                }
            }
        }
    }
}

@Composable
private fun stageTitle(stage: RideStage): String =
    stringResource(
        when (stage) {
            RideStage.TO_PICKUP -> R.string.ride_title_to_pickup
            RideStage.ARRIVED -> R.string.ride_title_arrived
            RideStage.IN_TRIP -> R.string.ride_title_in_trip
            RideStage.PAYMENT -> R.string.ride_title_payment
            RideStage.FINISHED -> R.string.ride_title_finished
            RideStage.ENDED -> R.string.ride_title_ended
        },
    )

@Composable
private fun RideSummary(booking: BookingDetailResponse) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        booking.pickupAddress?.let { LabeledLine(stringResource(R.string.ride_pickup_label), it) }
        booking.destinationAddress?.let { LabeledLine(stringResource(R.string.ride_destination_label), it) }
        val fare = booking.finalFare ?: booking.estimatedFare
        val currency = booking.currency
        if (fare != null && currency != null) {
            LabeledLine(stringResource(R.string.ride_fare_label), "$currency $fare")
        }
        when (booking.paymentMethod) {
            BookingDetailResponse.PaymentMethod.CASH -> Text(stringResource(R.string.ride_payment_cash))
            BookingDetailResponse.PaymentMethod.ONLINE -> Text(stringResource(R.string.ride_payment_online))
            null -> Unit
        }
    }
}

@Composable
private fun LabeledLine(
    label: String,
    value: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
        Text(text = value)
    }
}

@Composable
private fun StageActions(
    state: ActiveRideUiState,
    stage: RideStage,
    booking: BookingDetailResponse,
    actions: ActiveRideActions,
) {
    val busy = state.isActionInFlight
    when (stage) {
        RideStage.TO_PICKUP -> {
            booking.pickupAddress?.let { address ->
                OutlinedButton(onClick = { actions.onNavigate(address) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.ride_navigate_action))
                }
            }
            Button(onClick = actions.onImArrived, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ride_arrived_action))
            }
        }
        RideStage.ARRIVED -> {
            SelfieSection(selfie = state.selfie, onRetry = actions.onRetrySelfie)
            PinEntry(busy = busy, onStart = actions.onStartRide)
        }
        RideStage.IN_TRIP -> InTripActions(booking = booking, busy = busy, actions = actions)
        RideStage.PAYMENT -> {
            PaymentActions(booking = booking, busy = busy, cashConfirmed = state.cashConfirmed, onConfirmCash = actions.onConfirmCash)
            // Confirming the cash is the driver's last duty; the booking itself settles on the server later.
            if (state.cashConfirmed) {
                Button(onClick = actions.onDone, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.ride_done_action))
                }
            }
        }
        RideStage.FINISHED -> DoneSection(stringResource(R.string.ride_finished_note), actions.onDone)
        RideStage.ENDED -> DoneSection(stringResource(R.string.ride_ended_note), actions.onDone)
    }
}

@Composable
private fun InTripActions(
    booking: BookingDetailResponse,
    busy: Boolean,
    actions: ActiveRideActions,
) {
    var showEndEarly by rememberSaveable { mutableStateOf(false) }
    booking.destinationAddress?.let { address ->
        OutlinedButton(onClick = { actions.onNavigate(address) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.ride_navigate_action))
        }
    }
    Button(onClick = actions.onComplete, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.ride_complete_action))
    }
    OutlinedButton(onClick = { showEndEarly = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.ride_end_early_action))
    }
    if (showEndEarly) {
        EndEarlyDialog(
            onConfirm = { reason, safety ->
                showEndEarly = false
                actions.onEndEarly(reason, safety)
            },
            onDismiss = { showEndEarly = false },
        )
    }
}

@Composable
private fun PaymentActions(
    booking: BookingDetailResponse,
    busy: Boolean,
    cashConfirmed: Boolean,
    onConfirmCash: () -> Unit,
) {
    when (booking.paymentMethod) {
        BookingDetailResponse.PaymentMethod.CASH ->
            if (cashConfirmed) {
                Text(stringResource(R.string.ride_cash_confirmed_note))
            } else {
                Button(onClick = onConfirmCash, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.ride_confirm_cash_action))
                }
            }
        else -> Text(stringResource(R.string.ride_payment_online_note))
    }
}

@Composable
private fun DoneSection(
    note: String,
    onDone: () -> Unit,
) {
    Text(note)
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.ride_done_action))
    }
}

@Composable
private fun PinEntry(
    busy: Boolean,
    onStart: (String) -> Unit,
) {
    // remember, not rememberSaveable: the PIN must never reach the saved-instance Bundle.
    var pin by remember { mutableStateOf("") }
    Text(stringResource(R.string.ride_pin_hint), style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = pin,
        onValueChange = { pin = it.filter(Char::isDigit).take(MAX_PIN_INPUT_LENGTH) },
        label = { Text(stringResource(R.string.ride_pin_label)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth().testTag("ride_pin_field"),
    )
    Button(onClick = { onStart(pin) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.ride_start_action))
    }
}

@Composable
private fun SelfieSection(
    selfie: SelfieState,
    onRetry: () -> Unit,
) {
    Text(stringResource(R.string.ride_selfie_title), style = MaterialTheme.typography.titleMedium)
    when (selfie) {
        SelfieState.Idle, SelfieState.Loading -> Text(stringResource(R.string.ride_selfie_loading))
        SelfieState.Unavailable -> {
            Text(stringResource(R.string.ride_selfie_unavailable))
            TextButton(onClick = onRetry) { Text(stringResource(R.string.ride_selfie_retry)) }
        }
        is SelfieState.Loaded -> {
            val bitmap = remember(selfie.image) { BitmapFactory.decodeByteArray(selfie.image.bytes, 0, selfie.image.bytes.size) }
            if (bitmap == null) {
                Text(stringResource(R.string.ride_selfie_unavailable))
            } else {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.ride_selfie_content_description),
                    modifier = Modifier.fillMaxWidth().height(SELFIE_HEIGHT),
                )
            }
        }
    }
}

@Composable
private fun EndEarlyDialog(
    onConfirm: (reason: String, safetyConcern: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var reason by rememberSaveable { mutableStateOf("") }
    var safety by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ride_end_early_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text(stringResource(R.string.ride_end_early_reason_label)) },
                    modifier = Modifier.fillMaxWidth().testTag("end_early_reason"),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.toggleable(value = safety, role = Role.Checkbox, onValueChange = { safety = it }),
                ) {
                    Checkbox(checked = safety, onCheckedChange = null)
                    Text(stringResource(R.string.ride_end_early_safety_label))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reason, safety) }, enabled = reason.isNotBlank()) {
                Text(stringResource(R.string.ride_end_early_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ride_end_early_cancel)) }
        },
    )
}

/** Sets `FLAG_SECURE` on the hosting window while [enabled]; a no-op when the composition isn't hosted by an Activity. */
@Composable
fun SecureWindowEffect(enabled: Boolean) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity, enabled) {
        val window = activity?.window
        if (enabled) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (enabled) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

private const val MAX_PIN_INPUT_LENGTH = 6
private val SELFIE_HEIGHT = 240.dp
