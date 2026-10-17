package com.ladygo.customer.booking

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ladygo.customer.api.generated.models.BookingDetailResponse
import com.ladygo.customer.api.generated.models.BookingDetailResponse.Status
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** What the track screen shows - always derived from the server's [Status], never authored client-side. */
enum class TrackStage {
    SEARCHING,
    DRIVER_COMING,
    IN_TRIP,
    PAYMENT,
    FINISHED,
    CANCELLED,
    ;

    /** The ride can still be cancelled by the rider (the server enforces it; this only hides the button). */
    val canCancel: Boolean get() = this == SEARCHING || this == DRIVER_COMING

    /** Call 112 is offered across the window trip sharing / safety applies to. */
    val showsEmergencyCall: Boolean get() = this == DRIVER_COMING || this == IN_TRIP

    val isLive: Boolean get() = this != FINISHED && this != CANCELLED
}

fun stageFor(status: Status?): TrackStage? =
    when (status) {
        Status.REQUESTED, Status.SEARCHING_DRIVER -> TrackStage.SEARCHING
        Status.DRIVER_ASSIGNED, Status.DRIVER_ACCEPTED, Status.DRIVER_ARRIVING, Status.DRIVER_ARRIVED -> TrackStage.DRIVER_COMING
        Status.RIDE_STARTED -> TrackStage.IN_TRIP
        Status.RIDE_COMPLETED, Status.PAYMENT_PENDING -> TrackStage.PAYMENT
        Status.COMPLETED -> TrackStage.FINISHED
        Status.CANCELLED -> TrackStage.CANCELLED
        null -> null
    }

data class TrackUiState(
    val booking: BookingDetailResponse? = null,
    val stage: TrackStage? = null,
    val isCancelling: Boolean = false,
    val error: String? = null,
    /** The booking is not this rider's / no longer exists - the nav layer leaves the screen. */
    val gone: Boolean = false,
)

private const val DEFAULT_CANCEL_REASON = "Changed my mind"

@HiltViewModel
class TrackBookingViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val repository: BookingRepository,
    ) : ViewModel() {
        private val bookingId: UUID = UUID.fromString(checkNotNull(savedStateHandle.get<String>(BOOKING_ID_ARG)))

        private var refreshJob: Job? = null

        private val _uiState = MutableStateFlow(TrackUiState())
        val uiState: StateFlow<TrackUiState> = _uiState.asStateFlow()

        /** One server read; the screen calls this on a timer while visible. Safe to call concurrently
         *  with a cancel - a cancel result is applied in order by the caller of each. */
        fun refresh() {
            refreshJob?.cancel() // a slow, older poll must not land after (and overwrite) a newer one
            refreshJob =
                viewModelScope.launch {
                    when (val outcome = repository.fetch(bookingId)) {
                        is BookingFetchOutcome.Found -> apply(outcome.booking)
                        BookingFetchOutcome.NotFound -> _uiState.update { it.copy(gone = true) }
                        // Transient: keep showing the last known state; the next tick retries.
                        is BookingFetchOutcome.NetworkFailure -> _uiState.update { it.copy(error = outcome.message) }
                    }
                }
        }

        fun onCancel(reason: String) {
            val current = _uiState.value
            if (current.isCancelling || current.stage?.canCancel != true) return
            _uiState.update { it.copy(isCancelling = true, error = null) }
            viewModelScope.launch {
                when (val outcome = repository.cancel(bookingId, reason.trim().ifEmpty { DEFAULT_CANCEL_REASON })) {
                    is CancelOutcome.Cancelled -> apply(outcome.booking)
                    is CancelOutcome.Rejected -> {
                        _uiState.update { it.copy(isCancelling = false, error = outcome.message) }
                        refresh() // the rejection may mean the ride moved on (e.g. already started)
                    }
                    is CancelOutcome.NetworkFailure -> {
                        _uiState.update { it.copy(isCancelling = false, error = outcome.message) }
                        refresh() // the cancel may have landed
                    }
                }
            }
        }

        private fun apply(booking: BookingDetailResponse) {
            val current = _uiState.value.stage
            val stage = stageFor(booking.status) ?: current
            // A slow poll overtaken by a cancel (or by the trip starting) must not drag the screen back.
            if (current != null && stage != null && isStale(current, stage)) return
            _uiState.update { it.copy(booking = booking, stage = stage, isCancelling = false, error = null) }
        }

        private fun isStale(
            current: TrackStage,
            incoming: TrackStage,
        ): Boolean =
            when {
                !current.isLive -> incoming.isLive // finished / cancelled is final
                current == TrackStage.IN_TRIP || current == TrackStage.PAYMENT ->
                    incoming == TrackStage.SEARCHING || incoming == TrackStage.DRIVER_COMING
                else -> false // a driver may legitimately drop off and the search restart
            }

        companion object {
            const val BOOKING_ID_ARG = "bookingId"
        }
    }
