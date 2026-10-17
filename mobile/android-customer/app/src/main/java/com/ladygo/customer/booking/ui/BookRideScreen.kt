package com.ladygo.customer.booking.ui

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ladygo.customer.R
import com.ladygo.customer.api.generated.models.BookingDetailResponse
import com.ladygo.customer.booking.BookRideUiState
import com.ladygo.customer.booking.BookRideViewModel
import com.ladygo.customer.booking.BookStep
import com.ladygo.customer.booking.PickupState
import com.ladygo.customer.booking.Place
import com.ladygo.customer.ui.components.BannerKind
import com.ladygo.customer.ui.components.FareCard
import com.ladygo.customer.ui.components.InfoBanner
import com.ladygo.customer.ui.components.LadyGoCard
import com.ladygo.customer.ui.components.LadyGoScreen
import com.ladygo.customer.ui.components.LadyGoTextField
import com.ladygo.customer.ui.components.LoadingState
import com.ladygo.customer.ui.components.Money
import com.ladygo.customer.ui.components.PrimaryButton
import com.ladygo.customer.ui.components.QuietButton
import com.ladygo.customer.ui.components.RouteCard
import com.ladygo.customer.ui.components.SecondaryButton
import com.ladygo.customer.ui.components.SectionLabel
import com.ladygo.customer.ui.components.StateIllustration
import com.ladygo.customer.ui.components.StatusChip
import com.ladygo.customer.ui.theme.LadyGoTheme

@Composable
fun BookRideScreen(
    onBooked: (BookingDetailResponse) -> Unit,
    onClose: () -> Unit,
    viewModel: BookRideViewModel = hiltViewModel(),
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    // FINE and COARSE are requested together: on Android 12+ a lone FINE request is ignored by the system.
    val locationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { viewModel.locate() }

    LaunchedEffect(Unit) { viewModel.locateIfNeeded() }
    LaunchedEffect(state.booked) {
        state.booked?.let {
            onBooked(it)
            viewModel.onBookingConsumed()
        }
    }
    // Not while a request is in flight or its outcome is unknown: Back must not drop the idempotency key.
    BackHandler(enabled = state.step != BookStep.DESTINATION && !state.isBusy && !state.createUncertain) {
        if (state.step == BookStep.SELFIE) viewModel.onSelfieCancelled() else viewModel.onChangeDestination()
    }

    if (state.step == BookStep.SELFIE) {
        SelfieCaptureScreen(
            isUploading = state.isBusy,
            error = state.error,
            onCaptured = viewModel::onSelfieCaptured,
            onCancel = viewModel::onSelfieCancelled,
        )
    } else {
        BookRideContent(
            state = state,
            actions =
                BookRideActions(
                    onGrantLocation = {
                        locationLauncher.launch(
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                        )
                    },
                    onOpenSettings = { openAppSettings(context) },
                    onRetryLocation = viewModel::locate,
                    onQueryChanged = viewModel::onQueryChanged,
                    onSearch = viewModel::onSearch,
                    onPickDestination = viewModel::onDestinationPicked,
                    onChangeDestination = viewModel::onChangeDestination,
                    onBook = viewModel::onBookTapped,
                    onBack = onClose,
                ),
        )
    }
}

@Composable
fun BookRideContent(
    state: BookRideUiState,
    actions: BookRideActions,
) {
    val ready = state.pickup as? PickupState.Ready
    val estimateStep = ready != null && state.step == BookStep.ESTIMATE
    LadyGoScreen(
        title = stringResource(R.string.book_title),
        onBack = actions.onBack,
        backDescription = stringResource(R.string.book_back_action),
        bottomBar =
            if (estimateStep) {
                {
                    PrimaryButton(
                        text = stringResource(R.string.book_confirm_action),
                        onClick = actions.onBook,
                        enabled = state.estimate != null,
                        loading = state.isBusy,
                    )
                    QuietButton(
                        text = stringResource(R.string.book_change_destination_action),
                        onClick = actions.onChangeDestination,
                        enabled = !state.isBusy && !state.createUncertain,
                    )
                }
            } else {
                null
            },
    ) {
        state.error?.let { InfoBanner(text = it, kind = BannerKind.ERROR, modifier = Modifier.testTag("book_error")) }
        when {
            ready == null -> PickupPending(state.pickup, actions)
            state.step == BookStep.ESTIMATE -> EstimateSection(state, ready.place)
            else -> DestinationSection(state, ready.place, actions)
        }
    }
}

@Composable
private fun PickupPending(
    pickup: PickupState,
    actions: BookRideActions,
) {
    when (pickup) {
        PickupState.Locating -> LoadingState(message = stringResource(R.string.book_locating))
        PickupState.NeedsPermission -> {
            StateIllustration(
                icon = Icons.Filled.LocationOn,
                title = stringResource(R.string.book_location_title),
                body = stringResource(R.string.book_location_rationale),
            )
            PrimaryButton(text = stringResource(R.string.book_grant_location_action), onClick = actions.onGrantLocation)
            SecondaryButton(text = stringResource(R.string.book_open_settings_action), onClick = actions.onOpenSettings)
        }
        PickupState.Unavailable -> {
            StateIllustration(
                icon = Icons.Filled.LocationOff,
                title = stringResource(R.string.book_location_unavailable_title),
                body = stringResource(R.string.book_location_unavailable),
            )
            PrimaryButton(text = stringResource(R.string.book_retry_action), onClick = actions.onRetryLocation)
        }
        is PickupState.Ready -> Unit
    }
}

@Composable
private fun DestinationSection(
    state: BookRideUiState,
    pickup: Place,
    actions: BookRideActions,
) {
    LadyGoCard {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.size(14.dp).clip(CircleShape).background(LadyGoTheme.extra.success),
            )
            Column {
                Text(
                    stringResource(R.string.book_pickup_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(pickup.address, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("book_pickup_address"))
            }
        }
    }
    LadyGoTextField(
        value = state.query,
        onValueChange = actions.onQueryChanged,
        label = stringResource(R.string.book_destination_label),
        leadingIcon = Icons.Filled.Search,
        helper = stringResource(R.string.book_destination_helper),
        modifier = Modifier.testTag("book_destination_field"),
    )
    SecondaryButton(
        text = stringResource(R.string.book_search_action),
        onClick = actions.onSearch,
        enabled = state.query.trim().length >= MIN_QUERY_LENGTH && !state.isBusy,
    )
    if (state.isBusy) LoadingState()
    if (state.searched && state.candidates.isEmpty() && !state.isBusy) {
        InfoBanner(text = stringResource(R.string.book_no_results), kind = BannerKind.WARNING)
    }
    if (state.candidates.isNotEmpty()) SectionLabel(stringResource(R.string.book_results_label))
    state.candidates.forEach { place ->
        LadyGoCard(modifier = Modifier.clickable(enabled = !state.isBusy) { actions.onPickDestination(place) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Place, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                Text(place.address, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun EstimateSection(
    state: BookRideUiState,
    pickup: Place,
) {
    RouteCard(
        pickupLabel = stringResource(R.string.book_pickup_label),
        pickup = pickup.address,
        destinationLabel = stringResource(R.string.book_destination_label),
        destination = state.destination?.address,
    )
    val estimate = state.estimate
    if (estimate?.estimatedFare != null) {
        FareCard(
            label = stringResource(R.string.book_fare_label),
            amount = Money.format(estimate.estimatedFare, estimate.currency),
            caption = stringResource(R.string.book_payment_cash),
            note = if (estimate.approximate == true) stringResource(R.string.book_fare_approximate) else null,
            modifier = Modifier.testTag("book_fare"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            estimate.distanceMeters?.let {
                StatusChip(text = "%.1f km".format(it / METERS_PER_KM), kind = BannerKind.INFO, icon = Icons.Filled.Straighten)
            }
            estimate.durationSeconds?.let {
                StatusChip(
                    text = "${(it + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE} min",
                    kind = BannerKind.INFO,
                    icon = Icons.Filled.Schedule,
                )
            }
        }
    }
    InfoBanner(text = stringResource(R.string.book_selfie_heads_up), kind = BannerKind.INFO)
}

private const val MIN_QUERY_LENGTH = 3
private const val METERS_PER_KM = 1000.0
private const val SECONDS_PER_MINUTE = 60
