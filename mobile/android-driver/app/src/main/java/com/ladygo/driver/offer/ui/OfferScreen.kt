package com.ladygo.driver.offer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import com.ladygo.driver.ui.components.BannerKind
import com.ladygo.driver.ui.components.FareCard
import com.ladygo.driver.ui.components.InfoBanner
import com.ladygo.driver.ui.components.LadyGoScreen
import com.ladygo.driver.ui.components.Money
import com.ladygo.driver.ui.components.PrimaryButton
import com.ladygo.driver.ui.components.SecondaryButton
import com.ladygo.driver.ui.components.StatusChip
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
    // The first remaining time we see is the offer window - the ring drains from full to empty.
    var totalMillis by remember(state.offer.offerId) { mutableStateOf(0L) }

    LaunchedEffect(state.offer.offerId, expiresAtMillis, state.skew) {
        while (isActive) {
            val countdown = OfferCountdown(expiresAtMillis, state.skew)
            remainingMillis = countdown.remainingMillis()
            if (totalMillis == 0L) totalMillis = remainingMillis
            if (countdown.isExpired()) {
                onCountdownExpired()
                break
            }
            delay(COUNTDOWN_TICK_MILLIS)
        }
    }

    val canStillAct = !state.isActionInFlight && remainingMillis > 0
    val seconds = remainingMillis / MILLIS_PER_SECOND
    val progress = if (totalMillis > 0) (remainingMillis.toFloat() / totalMillis).coerceIn(0f, 1f) else 0f

    LadyGoScreen(
        bottomBar = {
            PrimaryButton(
                text = stringResource(R.string.offer_accept_action),
                onClick = onAccept,
                enabled = canStillAct,
                loading = state.isActionInFlight,
            )
            SecondaryButton(text = stringResource(R.string.offer_decline_action), onClick = onDecline, enabled = canStillAct)
        },
    ) {
        Spacer(Modifier.height(24.dp))
        StatusChip(text = stringResource(R.string.offer_screen_title), kind = BannerKind.INFO, icon = Icons.Filled.DirectionsCar)
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(168.dp),
                strokeWidth = 10.dp,
                color = if (seconds <= LOW_TIME_SECONDS) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primaryContainer,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${seconds}s", style = MaterialTheme.typography.displayMedium)
                Text(
                    stringResource(R.string.offer_time_left),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val fare = state.offer.estimatedFare
        if (fare != null) {
            FareCard(
                label = stringResource(R.string.offer_fare_label),
                amount = Money.format(fare, state.offer.currency),
                caption = stringResource(R.string.offer_cash_caption),
            )
        }
        state.offer.distanceMeters?.let { meters ->
            StatusChip(
                text = stringResource(R.string.offer_distance, meters / METERS_PER_KM),
                kind = BannerKind.INFO,
                icon = Icons.Filled.Place,
            )
        }
        InfoBanner(text = stringResource(R.string.offer_privacy_note), kind = BannerKind.INFO, icon = Icons.Filled.Lock)
        state.error?.let { InfoBanner(text = it, kind = BannerKind.ERROR) }
    }
}

private const val COUNTDOWN_TICK_MILLIS = 200L
private const val MILLIS_PER_SECOND = 1_000L
private const val LOW_TIME_SECONDS = 5L
private const val METERS_PER_KM = 1000.0
