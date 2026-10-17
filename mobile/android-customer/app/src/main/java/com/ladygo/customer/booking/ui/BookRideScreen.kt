package com.ladygo.customer.booking.ui

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
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
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding).padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.book_title), style = MaterialTheme.typography.headlineMedium)
            PickupSection(state.pickup, actions)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("book_error")) }
            if (state.pickup is PickupState.Ready) {
                when (state.step) {
                    BookStep.DESTINATION -> DestinationSection(state, actions)
                    BookStep.ESTIMATE -> EstimateSection(state, actions)
                    BookStep.SELFIE -> Unit // rendered full-screen by BookRideScreen
                }
            }
            OutlinedButton(onClick = actions.onBack, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.book_back_action))
            }
        }
    }
}

@Composable
private fun PickupSection(
    pickup: PickupState,
    actions: BookRideActions,
) {
    when (pickup) {
        PickupState.Locating -> {
            Text(stringResource(R.string.book_locating))
            CircularProgressIndicator()
        }
        is PickupState.Ready -> {
            Text(stringResource(R.string.book_pickup_label), style = MaterialTheme.typography.labelLarge)
            Text(pickup.place.address, modifier = Modifier.testTag("book_pickup_address"))
        }
        PickupState.NeedsPermission -> {
            Text(stringResource(R.string.book_location_rationale))
            Button(onClick = actions.onGrantLocation, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.book_grant_location_action))
            }
            OutlinedButton(onClick = actions.onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.book_open_settings_action))
            }
        }
        PickupState.Unavailable -> {
            Text(stringResource(R.string.book_location_unavailable))
            Button(onClick = actions.onRetryLocation, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.book_retry_action))
            }
        }
    }
}

@Composable
private fun DestinationSection(
    state: BookRideUiState,
    actions: BookRideActions,
) {
    OutlinedTextField(
        value = state.query,
        onValueChange = actions.onQueryChanged,
        label = { Text(stringResource(R.string.book_destination_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("book_destination_field"),
    )
    Button(
        onClick = actions.onSearch,
        enabled = state.query.trim().length >= MIN_QUERY_LENGTH && !state.isBusy,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.book_search_action)) }
    if (state.isBusy) CircularProgressIndicator()
    if (state.searched && state.candidates.isEmpty() && !state.isBusy) Text(stringResource(R.string.book_no_results))
    state.candidates.forEach { place ->
        Column(modifier = Modifier.fillMaxWidth().clickable(enabled = !state.isBusy) { actions.onPickDestination(place) }) {
            Text(place.address, modifier = Modifier.padding(vertical = 8.dp))
            HorizontalDivider()
        }
    }
}

@Composable
private fun EstimateSection(
    state: BookRideUiState,
    actions: BookRideActions,
) {
    val destination = state.destination
    val estimate = state.estimate
    destination?.let {
        Text(stringResource(R.string.book_destination_label), style = MaterialTheme.typography.labelLarge)
        Text(it.address)
    }
    if (estimate?.estimatedFare != null) {
        val fare = "${estimate.currency.orEmpty()} ${estimate.estimatedFare}".trim()
        Text(stringResource(R.string.book_fare_label), style = MaterialTheme.typography.labelLarge)
        Text(fare, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("book_fare"))
        if (estimate.approximate == true) Text(stringResource(R.string.book_fare_approximate))
    }
    Text(stringResource(R.string.book_payment_cash))
    if (state.isBusy) CircularProgressIndicator()
    Button(onClick = actions.onBook, enabled = !state.isBusy && estimate != null, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.book_confirm_action))
    }
    OutlinedButton(
        onClick = actions.onChangeDestination,
        enabled = !state.isBusy && !state.createUncertain,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.book_change_destination_action))
    }
}

private const val MIN_QUERY_LENGTH = 3
