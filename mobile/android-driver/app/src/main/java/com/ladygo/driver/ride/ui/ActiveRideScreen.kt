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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.maps.model.LatLng
import com.ladygo.driver.R
import com.ladygo.driver.api.generated.models.BookingDetailResponse
import com.ladygo.driver.map.RideFocus
import com.ladygo.driver.map.RideMap
import com.ladygo.driver.ride.ActiveRideUiState
import com.ladygo.driver.ride.ActiveRideViewModel
import com.ladygo.driver.ride.ExternalIntents
import com.ladygo.driver.ride.RideStage
import com.ladygo.driver.ride.SelfieState
import com.ladygo.driver.ui.components.BannerKind
import com.ladygo.driver.ui.components.EmergencyButton
import com.ladygo.driver.ui.components.FareCard
import com.ladygo.driver.ui.components.InfoBanner
import com.ladygo.driver.ui.components.LadyGoCard
import com.ladygo.driver.ui.components.LadyGoScreen
import com.ladygo.driver.ui.components.LadyGoTextField
import com.ladygo.driver.ui.components.LoadingState
import com.ladygo.driver.ui.components.Money
import com.ladygo.driver.ui.components.PrimaryButton
import com.ladygo.driver.ui.components.QuietButton
import com.ladygo.driver.ui.components.RouteCard
import com.ladygo.driver.ui.components.SecondaryButton
import com.ladygo.driver.ui.components.StateIllustration
import com.ladygo.driver.ui.components.StepDots
import com.ladygo.driver.ui.theme.LadyGoTheme
import java.util.UUID

@Composable
fun ActiveRideScreen(
    onFinished: () -> Unit,
    onRideCompleted: (UUID) -> Unit = {},
    viewModel: ActiveRideViewModel = hiltViewModel(),
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    // The driver can only leave through "Back to home" once the ride is over; an ongoing ride has no
    // dismiss path (and system Back must not be one).
    BackHandler(enabled = true) {}

    val noMapsMessage = stringResource(R.string.ride_no_navigation_app)
    val dialerFailedMessage = stringResource(R.string.ride_dialer_failed)
    ActiveRideContent(
        state = state,
        actions =
            ActiveRideActions(
                onImArrived = viewModel::onImArrived,
                onStartRide = viewModel::onStartRide,
                onComplete = viewModel::onComplete,
                onEndEarly = viewModel::onEndEarly,
                onCantComplete = viewModel::onCantCompleteRide,
                onConfirmCash = viewModel::onConfirmCashCollected,
                onRetrySelfie = viewModel::onRetrySelfie,
                onNavigate = { address ->
                    if (!ExternalIntents.navigateTo(context, address)) {
                        Toast.makeText(context, noMapsMessage, Toast.LENGTH_SHORT).show()
                    }
                },
                onCall112 = {
                    // Long, and worded as an instruction: in an emergency a silent no-op is the worst outcome.
                    if (!ExternalIntents.dialEmergency(context)) Toast.makeText(context, dialerFailedMessage, Toast.LENGTH_LONG).show()
                },
                onDone = {
                    viewModel.onDone()?.let(onRideCompleted)
                    onFinished()
                },
            ),
        map = { booking, stage ->
            val pickup = latLngOrNull(booking.pickupLatitude, booking.pickupLongitude)
            val destination = latLngOrNull(booking.destinationLatitude, booking.destinationLongitude)
            RideMap(
                pickup = pickup,
                destination = destination,
                focus = if (stage == RideStage.IN_TRIP) RideFocus.DESTINATION else RideFocus.PICKUP,
                modifier = Modifier.fillMaxSize(),
            )
        },
    )
}

@Composable
fun ActiveRideContent(
    state: ActiveRideUiState,
    actions: ActiveRideActions,
    /** The ride map. A slot so screen tests never need Play services; the real screen passes [RideMap]. */
    map: @Composable (BookingDetailResponse, RideStage) -> Unit = { _, _ -> },
) {
    val booking = state.booking ?: return
    val stage = state.stage ?: return

    // PIN entry and the rider photo are the two sensitive surfaces (design doc §4): no screenshots or
    // screen recording while they are on screen.
    SecureWindowEffect(enabled = stage == RideStage.ARRIVED)

    LadyGoScreen(
        title = stageTitle(stage),
        bottomBar = { BottomActions(state, stage, actions) },
    ) {
        StageProgress(stage)
        if (stage == RideStage.TO_PICKUP || stage == RideStage.IN_TRIP) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(RIDE_MAP_HEIGHT_DP.dp)
                        .clip(MaterialTheme.shapes.large)
                        .testTag("ride_map"),
            ) {
                map(booking, stage)
            }
        }
        state.error?.let { InfoBanner(text = it, kind = BannerKind.ERROR, modifier = Modifier.testTag("ride_error")) }
        StageContent(state, stage, booking, actions)
    }
}

@Composable
private fun BottomActions(
    state: ActiveRideUiState,
    stage: RideStage,
    actions: ActiveRideActions,
) {
    val busy = state.isActionInFlight
    when (stage) {
        RideStage.TO_PICKUP ->
            PrimaryButton(
                text = stringResource(R.string.ride_arrived_action),
                onClick = actions.onImArrived,
                enabled = !busy,
                loading = busy,
            )
        RideStage.IN_TRIP ->
            PrimaryButton(
                text = stringResource(R.string.ride_complete_action),
                onClick = actions.onComplete,
                enabled = !busy,
                loading = busy,
            )
        RideStage.PAYMENT ->
            if (state.cashConfirmed || state.booking?.paymentMethod != BookingDetailResponse.PaymentMethod.CASH) {
                if (state.cashConfirmed) PrimaryButton(text = stringResource(R.string.ride_done_action), onClick = actions.onDone)
            } else {
                PrimaryButton(
                    text = stringResource(R.string.ride_confirm_cash_action),
                    onClick = actions.onConfirmCash,
                    enabled = !busy,
                    loading = busy,
                )
            }
        RideStage.FINISHED, RideStage.ENDED -> PrimaryButton(text = stringResource(R.string.ride_done_action), onClick = actions.onDone)
        RideStage.ARRIVED -> Unit // the Start button sits with the PIN field so it can read what was typed
    }
    if (stage.showsEmergencyCall) {
        EmergencyButton(text = stringResource(R.string.ride_call_112_action), onClick = actions.onCall112, icon = Icons.Filled.Phone)
    }
}

@Composable
private fun StageProgress(stage: RideStage) {
    val index =
        when (stage) {
            RideStage.TO_PICKUP -> 0
            RideStage.ARRIVED -> 1
            RideStage.IN_TRIP -> 2
            else -> 3
        }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StepDots(current = index, total = 4)
        Text(
            stringResource(R.string.ride_steps_caption),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StageContent(
    state: ActiveRideUiState,
    stage: RideStage,
    booking: BookingDetailResponse,
    actions: ActiveRideActions,
) {
    val busy = state.isActionInFlight
    when (stage) {
        RideStage.TO_PICKUP -> {
            RouteSummary(booking)
            booking.pickupAddress?.let {
                SecondaryButton(text = stringResource(R.string.ride_navigate_action), onClick = {
                    actions.onNavigate(it)
                }, icon = Icons.Filled.Navigation)
            }
            CantCompleteAction(busy = busy, onConfirm = actions.onCantComplete)
        }
        RideStage.ARRIVED -> {
            SelfieSection(selfie = state.selfie, onRetry = actions.onRetrySelfie)
            PinEntry(busy = busy, onStart = actions.onStartRide)
            CantCompleteAction(busy = busy, onConfirm = actions.onCantComplete)
        }
        RideStage.IN_TRIP -> InTripContent(booking, busy, actions)
        RideStage.PAYMENT -> PaymentContent(booking, state.cashConfirmed)
        RideStage.FINISHED ->
            StateIllustration(
                icon = Icons.Filled.CheckCircle,
                title = stringResource(R.string.ride_finished_title),
                body = stringResource(R.string.ride_finished_note),
                tint = LadyGoTheme.extra.success,
                container = LadyGoTheme.extra.successContainer,
            )
        RideStage.ENDED ->
            StateIllustration(
                icon = Icons.Filled.Info,
                title = stringResource(R.string.ride_ended_title),
                body = stringResource(R.string.ride_ended_note),
            )
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
private fun RouteSummary(booking: BookingDetailResponse) {
    RouteCard(
        pickupLabel = stringResource(R.string.ride_pickup_label),
        pickup = booking.pickupAddress,
        destinationLabel = stringResource(R.string.ride_destination_label),
        destination = booking.destinationAddress,
    )
    val fare = booking.finalFare ?: booking.estimatedFare
    if (fare != null) {
        val cash = booking.paymentMethod == BookingDetailResponse.PaymentMethod.CASH
        FareCard(
            label = stringResource(R.string.ride_fare_label),
            amount = Money.format(fare, booking.currency),
            caption = stringResource(if (cash) R.string.ride_payment_cash else R.string.ride_payment_online),
        )
    }
}

@Composable
private fun InTripContent(
    booking: BookingDetailResponse,
    busy: Boolean,
    actions: ActiveRideActions,
) {
    var showEndEarly by rememberSaveable { mutableStateOf(false) }
    RouteSummary(booking)
    booking.destinationAddress?.let {
        SecondaryButton(text = stringResource(R.string.ride_navigate_action), onClick = {
            actions.onNavigate(it)
        }, icon = Icons.Filled.Navigation)
    }
    QuietButton(text = stringResource(R.string.ride_end_early_action), onClick = { showEndEarly = true }, enabled = !busy)
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

/** The exit before the trip starts (rider doesn't match, can't take her): deliberately a quiet text button
 *  at the foot of the content, away from the primary action, and behind a confirm dialog. */
@Composable
private fun CantCompleteAction(
    busy: Boolean,
    onConfirm: (reason: String) -> Unit,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    QuietButton(
        text = stringResource(R.string.ride_cant_complete_action),
        onClick = { showDialog = true },
        enabled = !busy,
        modifier = Modifier.testTag("ride_cant_complete"),
    )
    if (showDialog) {
        CantCompleteDialog(
            onConfirm = { reason ->
                showDialog = false
                onConfirm(reason)
            },
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
private fun CantCompleteDialog(
    onConfirm: (reason: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var reason by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(stringResource(R.string.ride_cant_complete_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.ride_cant_complete_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text(stringResource(R.string.ride_cant_complete_reason_label)) },
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().testTag("cant_complete_reason"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reason) }, enabled = reason.isNotBlank()) {
                Text(stringResource(R.string.ride_cant_complete_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ride_cant_complete_keep)) }
        },
    )
}

@Composable
private fun PaymentContent(
    booking: BookingDetailResponse,
    cashConfirmed: Boolean,
) {
    val fare = booking.finalFare ?: booking.estimatedFare
    when (booking.paymentMethod) {
        BookingDetailResponse.PaymentMethod.CASH -> {
            if (fare != null) {
                FareCard(
                    label = stringResource(R.string.ride_collect_label),
                    amount = Money.format(fare, booking.currency),
                    caption = stringResource(R.string.ride_payment_cash),
                )
            }
            if (cashConfirmed) {
                InfoBanner(
                    text = stringResource(R.string.ride_cash_confirmed_note),
                    kind = BannerKind.SUCCESS,
                    icon = Icons.Filled.CheckCircle,
                )
            }
        }
        else -> InfoBanner(text = stringResource(R.string.ride_payment_online_note), kind = BannerKind.INFO)
    }
}

@Composable
private fun PinEntry(
    busy: Boolean,
    onStart: (String) -> Unit,
) {
    // remember, not rememberSaveable: the PIN must never reach the saved-instance Bundle.
    var pin by remember { mutableStateOf("") }
    LadyGoCard {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.ride_pin_label), style = MaterialTheme.typography.labelLarge)
        }
        Text(
            stringResource(R.string.ride_pin_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LadyGoTextField(
            value = pin,
            onValueChange = { pin = it.filter(Char::isDigit).take(MAX_PIN_INPUT_LENGTH) },
            label = stringResource(R.string.ride_pin_label),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.testTag("ride_pin_field"),
        )
        PrimaryButton(text = stringResource(R.string.ride_start_action), onClick = { onStart(pin) }, enabled = !busy, loading = busy)
    }
}

@Composable
private fun SelfieSection(
    selfie: SelfieState,
    onRetry: () -> Unit,
) {
    LadyGoCard {
        Text(stringResource(R.string.ride_selfie_title), style = MaterialTheme.typography.titleMedium)
        when (selfie) {
            SelfieState.Idle, SelfieState.Loading -> LoadingState(message = stringResource(R.string.ride_selfie_loading))
            SelfieState.Unavailable -> {
                InfoBanner(text = stringResource(R.string.ride_selfie_unavailable), kind = BannerKind.WARNING)
                QuietButton(text = stringResource(R.string.ride_selfie_retry), onClick = onRetry)
            }
            is SelfieState.Loaded -> {
                val bitmap = remember(selfie.image) { BitmapFactory.decodeByteArray(selfie.image.bytes, 0, selfie.image.bytes.size) }
                if (bitmap == null) {
                    InfoBanner(text = stringResource(R.string.ride_selfie_unavailable), kind = BannerKind.WARNING)
                } else {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.ride_selfie_content_description),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(SELFIE_HEIGHT).clip(MaterialTheme.shapes.large),
                    )
                }
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
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(stringResource(R.string.ride_end_early_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text(stringResource(R.string.ride_end_early_reason_label)) },
                    shape = MaterialTheme.shapes.medium,
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

private fun latLngOrNull(
    latitude: Double?,
    longitude: Double?,
): LatLng? = if (latitude != null && longitude != null) LatLng(latitude, longitude) else null

private const val RIDE_MAP_HEIGHT_DP = 220
