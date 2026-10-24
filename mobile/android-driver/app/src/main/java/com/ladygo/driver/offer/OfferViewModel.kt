package com.ladygo.driver.offer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ladygo.driver.api.generated.models.BookingDetailResponse
import com.ladygo.driver.api.generated.models.OfferResponse
import com.ladygo.driver.realtime.OfferCountdown
import com.ladygo.driver.realtime.ServerClockSkew
import com.ladygo.driver.realtime.parseIsoInstantMillis
import com.ladygo.driver.ride.RideActionOutcome
import com.ladygo.driver.ride.RideRepository
import com.ladygo.driver.ride.RideStateMachine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

sealed interface OfferResolutionOutcome {
    data class Accepted(
        val booking: BookingDetailResponse,
    ) : OfferResolutionOutcome

    data object Declined : OfferResolutionOutcome

    /** The local countdown hit zero with no action in flight - optimistic, no server round trip
     *  (design doc §1/§3 rule 7). A tap that beat the timer is handled by [OfferUiState.Showing.isActionInFlight]
     *  instead, never by this case. */
    data object Expired : OfferResolutionOutcome

    /** The server's own answer to accept/decline: already claimed, already expired server-side, or
     *  withdrawn/cancelled - its real message, not a generic one (design doc §3 rule 7). */
    data class NoLongerAvailable(
        val message: String,
    ) : OfferResolutionOutcome
}

sealed interface OfferUiState {
    /** No current offer - whatever screen hosts [OfferScreen] isn't shown. */
    data object None : OfferUiState

    data class Showing(
        val offer: OfferResponse,
        val skew: ServerClockSkew,
        val isActionInFlight: Boolean = false,
        val error: String? = null,
    ) : OfferUiState

    /** Terminal, one-shot - the nav layer reacts to this (pop back to Home, or forward to the
     *  slice-3 ride-loop stub on [OfferResolutionOutcome.Accepted]) and then calls
     *  [OfferViewModel.onResolutionConsumed]. */
    data class Resolved(
        val outcome: OfferResolutionOutcome,
    ) : OfferUiState
}

/**
 * The incoming-offer screen's state (design doc §1/§3). Observes [OfferCoordinator] - the
 * process-lifetime singleton that actually owns dedupe and the notification - rather than collecting
 * [com.ladygo.driver.realtime.RealtimeEvents] itself, since this ViewModel is Activity-scoped and
 * would miss anything pushed while no Activity is alive (fresh-context review finding, CRITICAL;
 * see [OfferCoordinator]'s own doc comment).
 */
@HiltViewModel
class OfferViewModel
    @Inject
    constructor(
        private val offerRepository: OfferRepository,
        private val offerCoordinator: OfferCoordinator,
        private val rideRepository: RideRepository,
    ) : ViewModel() {
        private var lastKnownSkew: ServerClockSkew = ServerClockSkew.NONE

        private val _uiState = MutableStateFlow<OfferUiState>(OfferUiState.None)
        val uiState: StateFlow<OfferUiState> = _uiState.asStateFlow()

        init {
            viewModelScope.launch {
                offerCoordinator.slot.collect { slot ->
                    if (slot is OfferSlot.Occupied && _uiState.value !is OfferUiState.Resolved) {
                        _uiState.value = OfferUiState.Showing(offer = slot.offer, skew = slot.skew)
                    }
                    // OfferSlot.Empty is deliberately not handled here - a resolution is always driven
                    // explicitly via resolve() below, not by observing the slot clearing.
                }
            }
        }

        /** Called on every `ON_RESUME` (design doc §3 rule 2 "on every app foreground/resume"), which
         *  also covers rule 4's app-kill-and-relaunch case via the very first resume after cold start -
         *  there is no separate "was this app just killed" signal to react to differently - and on
         *  every STOMP (re)connect (see [com.ladygo.driver.nav.LadyGoNavHost]), which supplies a fresh
         *  clock-skew sample. */
        fun reconcile(skew: ServerClockSkew = lastKnownSkew) {
            lastKnownSkew = skew
            viewModelScope.launch {
                when (val outcome = offerRepository.currentOffer()) {
                    is CurrentOfferOutcome.Present -> offerCoordinator.onReconciledOffer(outcome.offer, skew)
                    CurrentOfferOutcome.None -> offerCoordinator.onReconciledNone()
                    is CurrentOfferOutcome.Failed -> Unit // best-effort; the next resume/reconnect retries it
                }
            }
        }

        fun onAccept() {
            val showing = _uiState.value as? OfferUiState.Showing ?: return
            if (showing.isActionInFlight) return
            val offerId = showing.offer.offerId ?: return
            _uiState.value = showing.copy(isActionInFlight = true, error = null)
            viewModelScope.launch {
                when (val outcome = offerRepository.accept(offerId)) {
                    is AcceptOfferOutcome.Accepted -> resolve(offerId, OfferResolutionOutcome.Accepted(outcome.booking))
                    is AcceptOfferOutcome.Rejected ->
                        acceptedAnyway(showing.offer)?.let { resolve(offerId, OfferResolutionOutcome.Accepted(it)) }
                            ?: resolve(offerId, OfferResolutionOutcome.NoLongerAvailable(outcome.message))
                    is AcceptOfferOutcome.NetworkFailure ->
                        acceptedAnyway(showing.offer)?.let { resolve(offerId, OfferResolutionOutcome.Accepted(it)) }
                            ?: handleRetryableFailure(showing, offerId, outcome.message)
                }
            }
        }

        /** A lost or garbled accept response doesn't mean the accept didn't happen (the server may have
         *  committed it, then the response, or the retry's "no longer available", never reached us). After
         *  any non-success, ask the server about the booking itself: if it is already this driver's, the
         *  ride is accepted and must be tracked, or it would be orphaned (nothing else would ever show it). */
        private suspend fun acceptedAnyway(offer: OfferResponse): BookingDetailResponse? {
            val bookingId = offer.bookingId ?: return null
            val fetched = rideRepository.fetch(bookingId) as? RideActionOutcome.Success ?: return null
            return fetched.booking.takeIf { RideStateMachine.isAcceptedAndInProgress(it.status) }
        }

        fun onDecline() {
            val showing = _uiState.value as? OfferUiState.Showing ?: return
            if (showing.isActionInFlight) return
            val offerId = showing.offer.offerId ?: return
            _uiState.value = showing.copy(isActionInFlight = true, error = null)
            viewModelScope.launch {
                when (val outcome = offerRepository.decline(offerId)) {
                    DeclineOfferOutcome.Declined -> resolve(offerId, OfferResolutionOutcome.Declined)
                    is DeclineOfferOutcome.Rejected -> resolve(offerId, OfferResolutionOutcome.Declined)
                    is DeclineOfferOutcome.NetworkFailure -> handleRetryableFailure(showing, offerId, outcome.message)
                }
            }
        }

        /** A retryable (network/5xx) failure normally just re-enables the buttons with an error - but
         *  if the local countdown already hit zero *while the call was in flight*, there's no later
         *  tick left to notice that (the countdown's `LaunchedEffect` already exited after calling
         *  `onLocalCountdownExpired` once, which was a no-op while `isActionInFlight` was true), so
         *  this checks expiry itself before falling back to "show the error and let them retry"
         *  (design doc §3 rule 7; fresh-context review finding - without this the screen could get
         *  stuck at 0s with both buttons permanently disabled and no way out). */
        private fun handleRetryableFailure(
            showing: OfferUiState.Showing,
            offerId: UUID,
            message: String,
        ) {
            if (!isStillShowing(offerId)) return
            if (isExpiredNow(showing)) {
                resolve(offerId, OfferResolutionOutcome.Expired)
            } else {
                _uiState.value = showing.copy(isActionInFlight = false, error = message)
            }
        }

        private fun isExpiredNow(showing: OfferUiState.Showing): Boolean {
            val expiresAtMillis = parseIsoInstantMillis(showing.offer.expiresAt) ?: return true
            return OfferCountdown(expiresAtMillis, showing.skew).isExpired()
        }

        /** Guards a delayed network-failure update from clobbering state that has since moved on
         *  (e.g. a different offer already replaced this one defensively - [OfferInbox]). */
        private fun isStillShowing(offerId: UUID): Boolean = (_uiState.value as? OfferUiState.Showing)?.offer?.offerId == offerId

        /** The local countdown reaching zero with nothing in flight (design doc §3 rule 7) - a tap
         *  already in flight is left alone; its own response (accepted, or "no longer available") is
         *  what decides the outcome, never this local timer. */
        fun onLocalCountdownExpired() {
            val showing = _uiState.value as? OfferUiState.Showing ?: return
            if (showing.isActionInFlight) return
            val offerId = showing.offer.offerId ?: return
            resolve(offerId, OfferResolutionOutcome.Expired)
        }

        fun onResolutionConsumed() {
            // An offer that arrived while the Resolved state was up was skipped by the slot collector;
            // pick it up now rather than leaving it unseen until the next reconcile.
            val slot = offerCoordinator.slot.value
            _uiState.value =
                if (slot is OfferSlot.Occupied) OfferUiState.Showing(offer = slot.offer, skew = slot.skew) else OfferUiState.None
        }

        private fun resolve(
            offerId: UUID,
            outcome: OfferResolutionOutcome,
        ) {
            offerCoordinator.clearIfCurrent(offerId)
            _uiState.value = OfferUiState.Resolved(outcome)
        }
    }
