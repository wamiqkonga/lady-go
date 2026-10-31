package com.ladygo.customer.booking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ladygo.customer.api.generated.models.BookingDetailResponse
import com.ladygo.customer.api.generated.models.BookingRequest
import com.ladygo.customer.api.generated.models.FareEstimateResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.util.UUID
import javax.inject.Inject

enum class BookStep { DESTINATION, ESTIMATE, SELFIE }

sealed interface PickupState {
    data object Locating : PickupState

    data class Ready(
        val place: Place,
    ) : PickupState

    data object NeedsPermission : PickupState

    data object Unavailable : PickupState
}

/** The address behind the pin the rider is currently holding on the map. */
sealed interface PinState {
    /** No pin has settled yet (the map has not been moved, or the step just changed). */
    data object Idle : PinState

    /** The map stopped moving and the address is being looked up. */
    data object Resolving : PinState

    data class Resolved(
        val place: Place,
        /** `false`: Lady Go does not serve this point. `null`: unknown (the server was unreachable and only
         *  the device geocoder answered) - the server re-checks when the fare is quoted. */
        val inServiceArea: Boolean?,
    ) : PinState
}

data class BookRideUiState(
    val step: BookStep = BookStep.DESTINATION,
    /** The pin under the map's centre, and whether the pin is currently setting the pickup, not the destination. */
    val pin: PinState = PinState.Idle,
    val editingPickup: Boolean = false,
    val pickup: PickupState = PickupState.Locating,
    val query: String = "",
    val candidates: List<Place> = emptyList(),
    val searched: Boolean = false,
    val destination: Place? = null,
    val estimate: FareEstimateResponse? = null,
    /** A search, estimate, upload or booking call is in flight. */
    val isBusy: Boolean = false,
    /** A create whose outcome is unknown (the response was lost): the rider may only retry it - the same
     *  confirmation - until it resolves, so a second, different request can never double-book. */
    val createUncertain: Boolean = false,
    val error: String? = null,
    /** The server-created booking; one-shot - the nav layer moves on to tracking it. */
    val booked: BookingDetailResponse? = null,
)

/** A selfie is only valid for a short window server-side (30 min); re-capture well before that. */
internal const val SELFIE_REUSE_LIMIT_MILLIS = 25 * 60 * 1000L

/**
 * The booking flow: pickup (device location) -> destination -> server fare estimate -> fresh selfie ->
 * create. Every price, area and eligibility decision is the server's; this only carries the rider's
 * choices and re-confirms a changed fare instead of accepting it silently (docs/api/booking.md §2).
 */
@HiltViewModel
class BookRideViewModel
    @Inject
    constructor(
        private val repository: BookingRepository,
        private val locationProvider: LocationProvider,
        private val addressResolver: AddressResolver,
        private val pinLookup: PinLookup,
        private val clock: Clock,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(BookRideUiState())
        val uiState: StateFlow<BookRideUiState> = _uiState.asStateFlow()

        private var pinJob: Job? = null
        private var selfieId: UUID? = null
        private var selfieCapturedAt = 0L

        /** Reused across retries of the *same* confirmation so a lost response can never double-book;
         *  replaced when the rider re-confirms a changed fare (a genuinely new request). */
        private var idempotencyKey: String? = null

        /** Locate on entry - but never again once a pickup is known or the flow has moved past choosing a
         *  destination (a rotation must not blank the estimate or move the pickup under it). */
        fun locateIfNeeded() {
            val state = _uiState.value
            if (state.pickup is PickupState.Ready || state.step != BookStep.DESTINATION) return
            locate()
        }

        /** Locate the rider (after the permission result, or an explicit retry). */
        fun locate() {
            _uiState.update { it.copy(pickup = PickupState.Locating, error = null) }
            viewModelScope.launch {
                val pickup =
                    when (val location = locationProvider.current()) {
                        CurrentLocation.PermissionDenied -> PickupState.NeedsPermission
                        CurrentLocation.Unavailable -> PickupState.Unavailable
                        is CurrentLocation.Found -> {
                            val address = addressResolver.reverse(location.point) ?: FALLBACK_PICKUP_ADDRESS
                            PickupState.Ready(Place(address, location.point))
                        }
                    }
                _uiState.update { it.copy(pickup = pickup) }
            }
        }

        /** The rider started moving the map: the pin that was resolved no longer matches the crosshair, so drop it
         *  (and any lookup still in flight) until the map settles again. Without this, Confirm stays live on the
         *  old address while the map is under her finger. */
        fun onPinMoving() {
            if (_uiState.value.step != BookStep.DESTINATION) return
            pinJob?.cancel()
            _uiState.update { if (it.pin == PinState.Idle) it else it.copy(pin = PinState.Idle) }
        }

        /** The map stopped moving at [point]: look up its address (once, when it settles - ADR-011). Cancels any
         *  lookup for an earlier position so a slow answer can never label the wrong spot. Not blocked by a search
         *  or fare request in flight: the settle fires once, so dropping it would leave the pin stuck on Idle. */
        fun onPinSettled(point: GeoPoint) {
            if (_uiState.value.step != BookStep.DESTINATION) return
            pinJob?.cancel()
            _uiState.update { it.copy(pin = PinState.Resolving, error = null) }
            pinJob =
                viewModelScope.launch {
                    val resolved =
                        when (val outcome = pinLookup.lookup(point)) {
                            is PinLookupOutcome.Resolved ->
                                PinState.Resolved(Place(outcome.address, point), outcome.inServiceArea)
                            PinLookupOutcome.Unavailable ->
                                PinState.Resolved(Place(addressResolver.reverse(point) ?: FALLBACK_PIN_ADDRESS, point), null)
                        }
                    // The fare may have been requested while this was resolving; do not repaint the pin over it.
                    _uiState.update { if (it.step == BookStep.DESTINATION) it.copy(pin = resolved) else it }
                }
        }

        /** "Confirm" on the map: sets the pickup when she is moving it, otherwise asks for the fare to the pin. */
        fun onConfirmPin() {
            val state = _uiState.value
            val pin = state.pin as? PinState.Resolved ?: return
            if (state.isBusy) return
            if (pin.inServiceArea == false) {
                _uiState.update { it.copy(error = OUT_OF_AREA_MESSAGE) }
                return
            }
            if (state.editingPickup) {
                _uiState.update { it.copy(pickup = PickupState.Ready(pin.place), editingPickup = false, pin = PinState.Idle, error = null) }
            } else {
                val pickupPoint = (state.pickup as? PickupState.Ready)?.place?.point
                if (pickupPoint != null && isSameSpot(pickupPoint, pin.place.point)) {
                    _uiState.update { it.copy(error = SAME_SPOT_MESSAGE) }
                    return
                }
                onDestinationPicked(pin.place)
            }
        }

        /** Move the pickup pin instead of using the device location. */
        fun onEditPickup() {
            if (_uiState.value.isBusy) return
            pinJob?.cancel()
            _uiState.update { it.copy(editingPickup = true, pin = PinState.Idle, error = null) }
        }

        fun onCancelEditPickup() {
            pinJob?.cancel()
            _uiState.update { it.copy(editingPickup = false, pin = PinState.Idle, error = null) }
        }

        fun onQueryChanged(query: String) = _uiState.update { it.copy(query = query, error = null) }

        fun onSearch() {
            val query = _uiState.value.query.trim()
            if (query.length < MIN_QUERY_LENGTH || _uiState.value.isBusy) return
            _uiState.update { it.copy(isBusy = true, error = null) }
            viewModelScope.launch {
                val near = (_uiState.value.pickup as? PickupState.Ready)?.place?.point
                val found = addressResolver.search(query, near)
                _uiState.update { it.copy(isBusy = false, candidates = found, searched = true) }
            }
        }

        fun onDestinationPicked(place: Place) {
            val pickup = (_uiState.value.pickup as? PickupState.Ready)?.place ?: return
            if (_uiState.value.isBusy) return
            _uiState.update { it.copy(isBusy = true, error = null, destination = place) }
            viewModelScope.launch {
                when (val outcome = repository.estimate(pickup.point, place.point)) {
                    is EstimateOutcome.Success ->
                        _uiState.update { it.copy(isBusy = false, estimate = outcome.estimate, step = BookStep.ESTIMATE) }
                    is EstimateOutcome.Rejected -> failToDestination(outcome.message)
                    is EstimateOutcome.NetworkFailure -> failToDestination(outcome.message)
                }
            }
        }

        fun onChangeDestination() {
            val state = _uiState.value
            // Not while a request is in flight or may already have landed: dropping the key then is what
            // would let a second, different request create a second live ride.
            if (state.isBusy || state.createUncertain) return
            idempotencyKey = null
            _uiState.update {
                it.copy(step = BookStep.DESTINATION, pin = PinState.Idle, estimate = null, destination = null, error = null)
            }
        }

        /** "Book" tapped on the estimate: needs a fresh selfie first, unless one is still valid. */
        fun onBookTapped() {
            val state = _uiState.value
            if (state.isBusy || state.estimate == null) return
            if (selfieIsFresh()) {
                submit()
            } else {
                _uiState.update { it.copy(step = BookStep.SELFIE, error = null) }
            }
        }

        fun onSelfieCancelled() {
            if (_uiState.value.isBusy) return // an upload / booking is in flight; it will finish or fail on its own
            _uiState.update { it.copy(step = BookStep.ESTIMATE, error = null) }
        }

        fun onSelfieCaptured(jpeg: ByteArray) {
            if (_uiState.value.isBusy) return
            _uiState.update { it.copy(isBusy = true, error = null) }
            viewModelScope.launch {
                when (val outcome = repository.uploadSelfie(jpeg)) {
                    is SelfieUploadOutcome.Uploaded -> {
                        selfieId = outcome.selfieId
                        selfieCapturedAt = clock.millis()
                        _uiState.update { it.copy(isBusy = false) }
                        // Only if the rider is still on the selfie step (Back during the upload abandons it).
                        if (_uiState.value.step == BookStep.SELFIE) submit()
                    }
                    is SelfieUploadOutcome.Rejected -> _uiState.update { it.copy(isBusy = false, error = outcome.message) }
                    is SelfieUploadOutcome.NetworkFailure -> _uiState.update { it.copy(isBusy = false, error = outcome.message) }
                }
            }
        }

        fun onBookingConsumed() = _uiState.update { it.copy(booked = null) }

        private fun submit() {
            val state = _uiState.value
            val pickup = (state.pickup as? PickupState.Ready)?.place ?: return
            val destination = state.destination ?: return
            val fare = state.estimate?.estimatedFare ?: return
            val selfie = selfieId ?: return
            val key = idempotencyKey ?: UUID.randomUUID().toString().also { idempotencyKey = it }
            _uiState.update { it.copy(isBusy = true, error = null, step = BookStep.ESTIMATE) }
            val request =
                BookingRequest(
                    pickupLatitude = pickup.point.latitude,
                    pickupLongitude = pickup.point.longitude,
                    pickupAddress = pickup.address,
                    destinationLatitude = destination.point.latitude,
                    destinationLongitude = destination.point.longitude,
                    destinationAddress = destination.address,
                    vehicleType = BookingRequest.VehicleType.CAB,
                    paymentMethod = BookingRequest.PaymentMethod.CASH,
                    expectedFare = fare,
                    rideSelfieId = selfie,
                )
            viewModelScope.launch {
                when (val outcome = repository.create(key, request)) {
                    is CreateBookingOutcome.Created -> {
                        resetFlow()
                        _uiState.update { it.copy(isBusy = false, createUncertain = false, booked = outcome.booking) }
                    }
                    is CreateBookingOutcome.FareChanged -> {
                        idempotencyKey = null // the rider re-confirms a new price: a new request
                        _uiState.update {
                            it.copy(isBusy = false, createUncertain = false, estimate = outcome.freshEstimate, error = outcome.message)
                        }
                    }
                    is CreateBookingOutcome.Rejected -> {
                        selfieId = null // it may be the reason; a fresh one is cheap
                        idempotencyKey = null
                        _uiState.update { it.copy(isBusy = false, createUncertain = false, error = outcome.message) }
                    }
                    is CreateBookingOutcome.NetworkFailure -> onCreateUncertain(outcome.message)
                }
            }
        }

        /** The response was lost: the booking may exist. First ask the server whether the rider has a ride in
         *  progress (then it landed - go to it); otherwise keep the key so a retry is the *same* request. */
        private suspend fun onCreateUncertain(message: String) {
            when (val active = repository.findActiveBooking()) {
                is ActiveBookingOutcome.Found -> {
                    resetFlow()
                    _uiState.update { it.copy(isBusy = false, createUncertain = false, booked = active.booking) }
                }
                ActiveBookingOutcome.None, ActiveBookingOutcome.Failed ->
                    _uiState.update { it.copy(isBusy = false, createUncertain = true, error = message) }
            }
        }

        private fun isSameSpot(
            a: GeoPoint,
            b: GeoPoint,
        ): Boolean {
            val closeInLatitude = kotlin.math.abs(a.latitude - b.latitude) < SAME_SPOT_DEGREES
            val closeInLongitude = kotlin.math.abs(a.longitude - b.longitude) < SAME_SPOT_DEGREES
            return closeInLatitude && closeInLongitude
        }

        private fun selfieIsFresh(): Boolean = selfieId != null && clock.millis() - selfieCapturedAt < SELFIE_REUSE_LIMIT_MILLIS

        private fun failToDestination(message: String) = _uiState.update { it.copy(isBusy = false, error = message, destination = null) }

        private fun resetFlow() {
            selfieId = null
            idempotencyKey = null
        }

        private companion object {
            const val MIN_QUERY_LENGTH = 3
            const val FALLBACK_PICKUP_ADDRESS = "My current location"
            const val FALLBACK_PIN_ADDRESS = "Pinned location"
            const val SAME_SPOT_MESSAGE = "Your pickup and destination are the same place. Move the pin to where you are going."

            // About 22 m: closer than this is the same doorstep, not a ride.
            const val SAME_SPOT_DEGREES = 0.0002
            const val OUT_OF_AREA_MESSAGE = "Lady Go does not serve this area yet. Move the pin inside the service area."
        }
    }
