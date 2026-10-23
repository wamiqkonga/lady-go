package com.ladygo.driver.ride

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ladygo.driver.api.generated.models.BookingDetailResponse
import com.ladygo.driver.auth.SessionManager
import com.ladygo.driver.location.AppPreferences
import com.ladygo.driver.realtime.RealtimeEvent
import com.ladygo.driver.realtime.RealtimeEvents
import com.ladygo.driver.realtime.StompConnectionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

sealed interface SelfieState {
    data object Idle : SelfieState

    data object Loading : SelfieState

    data class Loaded(
        val image: SelfieImage,
    ) : SelfieState

    data object Unavailable : SelfieState
}

data class ActiveRideUiState(
    /** `null` when no ride is being tracked. */
    val booking: BookingDetailResponse? = null,
    val stage: RideStage? = null,
    val isActionInFlight: Boolean = false,
    val error: String? = null,
    val selfie: SelfieState = SelfieState.Idle,
    /** Set once the server accepted "cash collected" in this session; the booking payload doesn't say. */
    val cashConfirmed: Boolean = false,
) {
    val isActive: Boolean get() = booking != null && stage != null

    /** A ride that still needs the driver; the nav layer only pins the ride screen while this holds. */
    val isOngoing: Boolean get() = isActive && stage?.isOngoing == true
}

/**
 * The active-ride screen's state (design doc §3, §7). Activity-scoped like [com.ladygo.driver.offer.OfferViewModel]
 * so the ride survives navigation. The ride stage is always [RideStateMachine.stageFor] of the last
 * booking the *server* returned - an action's tap never moves it, only the response does.
 */
@HiltViewModel
class ActiveRideViewModel
    @Inject
    constructor(
        private val rideRepository: RideRepository,
        private val selfieRepository: RiderSelfieRepository,
        private val appPreferences: AppPreferences,
        private val stompConnectionManager: StompConnectionManager,
        realtimeEvents: RealtimeEvents,
        sessionManager: SessionManager,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(ActiveRideUiState())
        val uiState: StateFlow<ActiveRideUiState> = _uiState.asStateFlow()

        /** Bumped by [track] and [clearAll]: a fetch or action that was started for an earlier ride (or
         *  before a Done/logout) must not resurrect or overwrite what is on screen when it finally lands. */
        private var generation = 0

        private val _rideEndCount = MutableStateFlow(0)

        /** Bumped each time a ride the driver was on is closed out (Done, or a successful "can't complete").
         *  The server has just released the driver, so Home uses this to re-sync its availability. Not bumped
         *  by logout. */
        val rideEndCount: StateFlow<Int> = _rideEndCount.asStateFlow()

        init {
            viewModelScope.launch {
                realtimeEvents.events.filterIsInstance<RealtimeEvent.BookingUpdated>().collect { event ->
                    val tracked = trackedBookingId()
                    if (tracked != null && event.booking.id == tracked) applyBooking(event.booking, keepError = true)
                }
            }
            viewModelScope.launch {
                // Drops what is on screen but keeps the remembered booking id: this fires for a forced logout
                // too (refresh token expired/revoked), and that ride must resume after the next sign-in.
                // An explicit logout forgets the id itself ([com.ladygo.driver.auth.AuthRepository.logout]).
                sessionManager.isLoggedIn.collect { loggedIn -> if (!loggedIn) clearAll(forgetBooking = false) }
            }
        }

        /** Called when the driver accepts an offer (the accept response is the first confirmed state). */
        fun track(booking: BookingDetailResponse) {
            val id = booking.id ?: return
            generation++
            appPreferences.setActiveBookingId(id.toString())
            _uiState.value = ActiveRideUiState()
            applyBooking(booking)
        }

        /** On every resume and STOMP (re)connect (design doc §3 rules 2 and 4): re-fetch the tracked - or,
         *  after an app kill, the remembered - booking; the server's answer wins outright. */
        fun reconcile() {
            val id = trackedBookingId() ?: return
            val startedAt = generation
            viewModelScope.launch {
                val outcome = rideRepository.fetch(id)
                if (startedAt != generation) return@launch // Done / logout / a newer ride happened meanwhile
                when (outcome) {
                    is RideActionOutcome.Success -> applyBooking(outcome.booking, keepError = true)
                    is RideActionOutcome.Rejected ->
                        // Only 403/404 mean "not this driver's booking any more" (the backend 404s a cancelled
                        // or bounced booking for the driver). Any other 4xx is not proof - keep the ride.
                        if (outcome.httpCode == HTTP_FORBIDDEN || outcome.httpCode == HTTP_NOT_FOUND) onNoLongerTheDriversRide()
                    is RideActionOutcome.NetworkFailure -> Unit // best-effort; the next resume/reconnect retries
                }
            }
        }

        fun onImArrived() =
            runAction { id, booking ->
                if (booking.status == BookingDetailResponse.Status.DRIVER_ACCEPTED) {
                    val heading = rideRepository.arriving(id)
                    if (heading !is RideActionOutcome.Success) return@runAction heading
                    // Show the confirmed step even if "arrived" is then refused by the proximity gate.
                    applyBooking(heading.booking)
                }
                rideRepository.arrived(id)
            }

        fun onStartRide(pin: String) {
            val trimmed = pin.trim()
            if (trimmed.isEmpty()) {
                _uiState.update { it.copy(error = "Enter the ride PIN the rider reads out.") }
                return
            }
            runAction { id, _ -> rideRepository.start(id, trimmed) }
        }

        /** Before the trip starts only: cancel without penalty on an eligibility concern. On success the ride
         *  is forgotten and the screen returns to Home, like "Done". */
        fun onCantCompleteRide(reason: String) {
            val trimmed = reason.trim()
            if (trimmed.isEmpty()) {
                _uiState.update { it.copy(error = "Please say what happened.") }
                return
            }
            val current = _uiState.value
            val id = current.booking?.id ?: return
            if (current.isActionInFlight) return
            val startedAt = generation
            _uiState.update { it.copy(isActionInFlight = true, error = null) }
            viewModelScope.launch {
                val outcome = rideRepository.cancelForEligibilityConcern(id, trimmed)
                if (startedAt != generation) return@launch
                when (outcome) {
                    is RideActionOutcome.Success -> endRide()
                    is RideActionOutcome.Rejected -> {
                        _uiState.update { it.copy(isActionInFlight = false, error = outcome.message) }
                        reconcile()
                    }
                    is RideActionOutcome.NetworkFailure -> {
                        _uiState.update { it.copy(isActionInFlight = false, error = outcome.message) }
                        reconcile() // the cancel may have landed - never assume either way
                    }
                }
            }
        }

        fun onComplete() = runAction { id, _ -> rideRepository.complete(id) }

        fun onEndEarly(
            reason: String,
            safetyConcern: Boolean,
        ) {
            val trimmed = reason.trim()
            if (trimmed.isEmpty()) {
                _uiState.update { it.copy(error = "Please give a reason for ending the ride early.") }
                return
            }
            runAction { id, _ -> rideRepository.endEarly(id, trimmed, safetyConcern) }
        }

        fun onConfirmCashCollected() =
            runAction { id, _ ->
                rideRepository.confirmCashCollected(id).also { outcome ->
                    // "Already confirmed" means an earlier tap landed (lost response, or the app restarted):
                    // the goal state is reached, so don't leave a button that can only fail.
                    val alreadyConfirmed = outcome is RideActionOutcome.Rejected && outcome.message.contains(CASH_ALREADY_CONFIRMED)
                    if (outcome is RideActionOutcome.Success || alreadyConfirmed) _uiState.update { it.copy(cashConfirmed = true) }
                }
            }

        fun onRetrySelfie() = loadSelfie()

        fun onDone() = endRide()

        fun dismissError() = _uiState.update { it.copy(error = null) }

        private fun trackedBookingId(): UUID? =
            _uiState.value.booking?.id
                ?: appPreferences.activeBookingId()?.let { runCatching { UUID.fromString(it) }.getOrNull() }

        private fun runAction(block: suspend (UUID, BookingDetailResponse) -> RideActionOutcome) {
            val current = _uiState.value
            val booking = current.booking ?: return
            val id = booking.id ?: return
            if (current.isActionInFlight) return
            val startedAt = generation
            _uiState.update { it.copy(isActionInFlight = true, error = null) }
            viewModelScope.launch {
                val outcome = block(id, booking)
                if (startedAt != generation) return@launch // Done / logout / a newer ride happened meanwhile
                when (outcome) {
                    is RideActionOutcome.Success -> applyBooking(outcome.booking, fromAction = true)
                    is RideActionOutcome.Rejected -> {
                        _uiState.update { it.copy(isActionInFlight = false, error = outcome.message) }
                        reconcile() // the rejection may mean state moved under us (design doc §3 rule 1)
                    }
                    is RideActionOutcome.NetworkFailure -> {
                        _uiState.update { it.copy(isActionInFlight = false, error = outcome.message) }
                        reconcile() // the call may have landed - never assume either way (§3 rule 5)
                    }
                }
            }
        }

        /** The server says this booking is no longer the driver's (cancelled by the rider, or bounced back
         *  to the search pool). Show that plainly instead of silently dropping to Home. */
        private fun onNoLongerTheDriversRide() {
            appPreferences.setActiveBookingId(null)
            val state = _uiState.value
            if (state.booking == null) {
                clearAll() // restored after an app kill with nothing to show - just forget the pointer
            } else {
                _uiState.value = state.copy(stage = RideStage.ENDED, isActionInFlight = false, error = null, selfie = SelfieState.Idle)
            }
        }

        private fun applyBooking(
            booking: BookingDetailResponse,
            keepError: Boolean = false,
            fromAction: Boolean = false,
        ) {
            val before = _uiState.value
            // Server wins - but only a *newer* server answer. A slow fetch or push overtaken by an action's
            // own response is stale and would otherwise bounce the screen backwards.
            if (before.booking?.id == booking.id && RideStateMachine.isStaleRegression(before.booking?.status, booking.status)) {
                if (fromAction) _uiState.update { it.copy(isActionInFlight = false) }
                return
            }
            val stage = RideStateMachine.stageFor(booking.status) ?: before.stage
            _uiState.update {
                it.copy(
                    booking = booking,
                    stage = stage,
                    isActionInFlight = if (fromAction) false else it.isActionInFlight,
                    error = if (keepError) it.error else null,
                    selfie = if (stage == RideStage.ARRIVED) it.selfie else SelfieState.Idle,
                )
            }
            if (stage != null && !stage.isOngoing) {
                appPreferences.setActiveBookingId(null)
            } else if (stage != null) {
                // The driver is on a ride (accepted just now, or restored after an app kill - where Home's own
                // availability starts OFFLINE and never connects): the live connection is needed to see
                // rider cancellations, and no-op if it is already up.
                stompConnectionManager.connect()
            }
            if (stage == RideStage.ARRIVED && before.stage != RideStage.ARRIVED) loadSelfie()
        }

        private fun loadSelfie() {
            val id = _uiState.value.booking?.id ?: return
            if (_uiState.value.selfie is SelfieState.Loading) return
            _uiState.update { it.copy(selfie = SelfieState.Loading) }
            viewModelScope.launch {
                val result =
                    when (val outcome = selfieRepository.load(id)) {
                        is SelfieOutcome.Loaded -> SelfieState.Loaded(outcome.image)
                        SelfieOutcome.Unavailable -> SelfieState.Unavailable
                    }
                // Only if the driver is still on the ARRIVED stage of this same ride.
                _uiState.update { state ->
                    if (state.stage == RideStage.ARRIVED && state.booking?.id == id) state.copy(selfie = result) else state
                }
            }
        }

        private fun endRide() {
            val hadRide = _uiState.value.isActive
            clearAll()
            if (hadRide) _rideEndCount.update { it + 1 }
        }

        private fun clearAll(forgetBooking: Boolean = true) {
            generation++
            if (forgetBooking) appPreferences.setActiveBookingId(null)
            _uiState.value = ActiveRideUiState()
        }

        private companion object {
            const val HTTP_FORBIDDEN = 403
            const val HTTP_NOT_FOUND = 404
            const val CASH_ALREADY_CONFIRMED = "already been confirmed"
        }
    }
