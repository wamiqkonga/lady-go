package com.ladygo.customer.safety

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ladygo.customer.booking.CurrentLocation
import com.ladygo.customer.booking.GeoPoint
import com.ladygo.customer.booking.LocationProvider
import com.ladygo.customer.booking.TrackBookingViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

sealed interface SosStage {
    data object Idle : SosStage

    /** She tapped SOS: ask once before alerting anyone. */
    data object Confirming : SosStage

    data object Sending : SosStage

    /** Lady Go's team has been alerted; [location] and [contacts] drive the "tell my people" step. */
    data class Sent(
        val location: GeoPoint,
        val contacts: List<TrustedContact>,
    ) : SosStage

    /** The alert did not go through. She is told plainly, and Call 112 stays one tap away. */
    data class Failed(
        val message: String,
    ) : SosStage
}

data class SafetyUiState(
    val sos: SosStage = SosStage.Idle,
    val sharing: Boolean = false,
    /** A freshly made trip-share token, to be handed to the system share sheet exactly once. */
    val shareToken: String? = null,
    val shareError: String? = null,
)

/** SOS and trip sharing for the ride on screen. The booking id is the track screen's own nav argument. */
@HiltViewModel
class SafetyViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val source: SafetySource,
        private val location: LocationProvider,
    ) : ViewModel() {
        private val bookingId: UUID = UUID.fromString(checkNotNull(savedStateHandle.get<String>(TrackBookingViewModel.BOOKING_ID_ARG)))

        private val _uiState = MutableStateFlow(SafetyUiState())
        val uiState: StateFlow<SafetyUiState> = _uiState.asStateFlow()

        fun onSosTapped() = _uiState.update { it.copy(sos = SosStage.Confirming) }

        fun onSosDismissed() = _uiState.update { it.copy(sos = SosStage.Idle) }

        fun onSosConfirmed() {
            if (_uiState.value.sos == SosStage.Sending) return
            _uiState.update { it.copy(sos = SosStage.Sending) }
            viewModelScope.launch {
                val point =
                    when (val here = location.current()) {
                        is CurrentLocation.Found -> here.point
                        CurrentLocation.PermissionDenied ->
                            return@launch fail("Allow location for Lady Go so we can tell the team where you are, or call 112.")
                        CurrentLocation.Unavailable ->
                            return@launch fail("We could not find your location. Please call 112.")
                    }
                when (val result = source.sos(point, bookingId)) {
                    is SafetyResult.Ok -> {
                        val contacts = (source.contacts() as? SafetyResult.Ok)?.value.orEmpty()
                        _uiState.update { it.copy(sos = SosStage.Sent(point, contacts)) }
                    }
                    is SafetyResult.Failed -> fail("${result.message} Please call 112.")
                }
            }
        }

        private fun fail(message: String) = _uiState.update { it.copy(sos = SosStage.Failed(message)) }

        fun onShareTapped() {
            if (_uiState.value.sharing) return
            _uiState.update { it.copy(sharing = true, shareError = null) }
            viewModelScope.launch {
                when (val result = source.shareTrip(bookingId)) {
                    is SafetyResult.Ok -> _uiState.update { it.copy(sharing = false, shareToken = result.value) }
                    is SafetyResult.Failed -> _uiState.update { it.copy(sharing = false, shareError = result.message) }
                }
            }
        }

        /** The share sheet has been opened; the token is not kept. */
        fun onShareHandled() = _uiState.update { it.copy(shareToken = null) }
    }
