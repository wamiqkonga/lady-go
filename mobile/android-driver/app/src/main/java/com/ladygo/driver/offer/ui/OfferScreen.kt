package com.ladygo.driver.offer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ladygo.driver.R
import com.ladygo.driver.api.generated.models.BookingDetailResponse
import com.ladygo.driver.offer.OfferResolutionOutcome
import com.ladygo.driver.offer.OfferUiState
import com.ladygo.driver.offer.OfferViewModel
import com.ladygo.driver.realtime.OfferCountdown
import com.ladygo.driver.realtime.parseIsoInstantMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Full-screen, un-dismissable except by accept/decline/local-expiry (design doc §1) - there is no
 * back/close action here on purpose; [onAccepted]/[onDismissed] are the only ways out, both driven by
 * [OfferViewModel]'s own terminal [OfferUiState.Resolved] state, never a user-initiated nav action.
 */
@Composable
fun OfferScreen(
    onAccepted: (BookingDetailResponse) -> Unit,
    onDismissed: () -> Unit,
    viewModel: OfferViewModel = hiltViewModel(),
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value

    // "Un-dismissable except by accept/decline/local-expiry" means exactly that - system Back must
    // not be a fourth way out. Without this, Back pops this screen off the nav stack, the countdown's
    // LaunchedEffect never fires again (its keys haven't changed, since it's a different composable
    // instance now), and the ViewModel - and the ongoing notification - stay stuck believing the
    // offer is still showing until a later resume/reconnect reconcile catches it (fresh-context
    // review finding).
    BackHandler(enabled = true) {}

    LaunchedEffect(state) {
        val resolved = state as? OfferUiState.Resolved ?: return@LaunchedEffect
        when (val outcome = resolved.outcome) {
            is OfferResolutionOutcome.Accepted -> onAccepted(outcome.booking)
            else -> onDismissed()
        }
        viewModel.onResolutionConsumed()
    }

    if (state is OfferUiState.Showing) {
        OfferContent(
            state = state,
            onAccept = viewModel::onAccept,
            onDecline = viewModel::onDecline,
            onCountdownExpired = viewModel::onLocalCountdownExpired,
        )
    }
}

@Composable
fun OfferContent(
    state: OfferUiState.Showing,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onCountdownExpired: () -> Unit,
) {
    val expiresAtMillis = remember(state.offer.expiresAt) { parseIsoInstantMillis(state.offer.expiresAt) ?: 0L }
    var remainingMillis by remember(state.offer.offerId) { mutableStateOf(0L) }

    LaunchedEffect(state.offer.offerId, expiresAtMillis, state.skew) {
        while (isActive) {
            val countdown = OfferCountdown(expiresAtMillis, state.skew)
            remainingMillis = countdown.remainingMillis()
            if (countdown.isExpired()) {
                onCountdownExpired()
                break
            }
            delay(COUNTDOWN_TICK_MILLIS)
        }
    }

    val canStillAct = !state.isActionInFlight && remainingMillis > 0

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = stringResource(R.string.offer_screen_title), style = MaterialTheme.typography.headlineMedium)
            Text(
                text = "${remainingMillis / MILLIS_PER_SECOND}s",
                style = MaterialTheme.typography.displaySmall,
            )
            val fare = state.offer.estimatedFare
            val currency = state.offer.currency
            if (fare != null && currency != null) {
                Text(text = "$currency $fare", style = MaterialTheme.typography.headlineSmall)
            }
            state.error?.let { message ->
                Text(text = message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onAccept, enabled = canStillAct) {
                Text(stringResource(R.string.offer_accept_action))
            }
            Button(onClick = onDecline, enabled = canStillAct) {
                Text(stringResource(R.string.offer_decline_action))
            }
        }
    }
}

private const val COUNTDOWN_TICK_MILLIS = 200L
private const val MILLIS_PER_SECOND = 1_000L
