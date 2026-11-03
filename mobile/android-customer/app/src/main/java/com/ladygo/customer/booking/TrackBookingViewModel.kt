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

/** The cancel dialog: open exactly while this is non-null. */
sealed interface CancelPreview {
    data object Loading : CancelPreview

    data class Ready(
        val fee: Long,
        val currency: String?,
        val freeUntilMillis: Long?,
    ) : CancelPreview

    /** The fee could not be fetched; the dialog says so and still lets her cancel. */
    data object Unavailable : CancelPreview
}

data class TrackUiState(
    val booking: BookingDetailResponse? = null,
    val stage: TrackStage? = null,
    val isCancelling: Boolean = false,
    /** Where her driver is right now, for the map; `null` when not shown (no driver yet, not live, or the ride is over). */
    val driver: GeoPoint? = null,
    val cancelPreview: CancelPreview? = null,
    /** What the rider was told cancelling would cost, kept to say so on the cancelled screen. `null` when unknown. */
    val cancelFee: Long? = null,
    val cancelFeeCurrency: String? = null,
    val error: String? = null,
    /** The booking is not this rider's / no longer exists - the nav layer leaves the screen. */
    val gone: Boolean = false,
)

private const val DEFAULT_CANCEL_REASON = "Changed my mind"
private const val SAFETY_CANCEL_REASON = "Safety concern"

@HiltViewModel
class TrackBookingViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val repository: BookingRepository,
        private val driverLocations: DriverLocationSource,
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
                        is BookingFetchOutcome.Found -> {
                            apply(outcome.booking)
                            refreshDriverPosition()
                        }
                        BookingFetchOutcome.NotFound -> _uiState.update { it.copy(gone = true) }
                        // Transient: keep showing the last known state; the next tick retries.
                        is BookingFetchOutcome.NetworkFailure -> _uiState.update { it.copy(error = outcome.message) }
                    }
                }
        }

        /** The driver's position only matters while she is on her way or the trip is under way; anywhere else the car
         *  is dropped so the map never shows a driver the ride no longer has. */
        private suspend fun refreshDriverPosition() {
            val stage = _uiState.value.stage
            if (stage != TrackStage.DRIVER_COMING && stage != TrackStage.IN_TRIP) {
                _uiState.update { it.copy(driver = null) }
                return
            }
            when (val outcome = driverLocations.locate(bookingId)) {
                is DriverLocationOutcome.Live -> _uiState.update { it.copy(driver = outcome.point) }
                DriverLocationOutcome.NotLive -> _uiState.update { it.copy(driver = null) }
                DriverLocationOutcome.Failed -> Unit // keep the last position; the next poll retries
            }
        }

        /** Opens the cancel dialog and asks the server what cancelling costs right now. */
        fun onCancelRequested() {
            val current = _uiState.value
            if (current.isCancelling || current.stage?.canCancel != true) return
            _uiState.update { it.copy(cancelPreview = CancelPreview.Loading) }
            viewModelScope.launch {
                val preview =
                    when (val outcome = repository.cancellationPreview(bookingId)) {
                        is CancelPreviewOutcome.Ready -> CancelPreview.Ready(outcome.fee, outcome.currency, outcome.freeUntilMillis)
                        CancelPreviewOutcome.NotCancellable -> {
                            _uiState.update { it.copy(cancelPreview = null) }
                            refresh() // the ride moved on while she was deciding
                            return@launch
                        }
                        CancelPreviewOutcome.Failed -> CancelPreview.Unavailable
                    }
                // She may have closed the dialog while the fee was loading.
                _uiState.update { if (it.cancelPreview != null) it.copy(cancelPreview = preview) else it }
            }
        }

        fun onCancelDismissed() {
            _uiState.update { it.copy(cancelPreview = null) }
        }

        /** [unsafe] is the "I don't feel safe" cancel: fee-free, and flagged to our team by the server. */
        fun onCancel(
            reason: String,
            unsafe: Boolean = false,
        ) {
            val current = _uiState.value
            if (current.isCancelling || current.stage?.canCancel != true) return
            val told = current.cancelPreview as? CancelPreview.Ready
            _uiState.update { it.copy(isCancelling = true, cancelPreview = null, error = null) }
            viewModelScope.launch {
                val text = reason.trim().ifEmpty { if (unsafe) SAFETY_CANCEL_REASON else DEFAULT_CANCEL_REASON }
                val outcome = if (unsafe) repository.cancelForSafety(bookingId, text) else repository.cancel(bookingId, text)
                when (outcome) {
                    is CancelOutcome.Cancelled -> {
                        apply(outcome.booking)
                        // A safety cancel's fee is the server's call, so only a normal cancel repeats what she was told.
                        val fee = if (unsafe) null else told?.fee
                        _uiState.update { it.copy(cancelFee = fee, cancelFeeCurrency = told?.currency) }
                    }
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
