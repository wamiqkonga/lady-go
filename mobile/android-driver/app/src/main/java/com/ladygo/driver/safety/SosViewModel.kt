package com.ladygo.driver.safety

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

    data object Sent : SosStage

    /** The alert did not go through. She is told plainly, and Call 112 stays one tap away. */
    data class Failed(
        val message: String,
    ) : SosStage
}

/** The driver's SOS: confirm, send her location to the safety team, say plainly if it did not go. */
@HiltViewModel
class SosViewModel
    @Inject
    constructor(
        private val source: SosSource,
    ) : ViewModel() {
        private val _stage = MutableStateFlow<SosStage>(SosStage.Idle)
        val stage: StateFlow<SosStage> = _stage.asStateFlow()

        fun onTapped() = _stage.update { SosStage.Confirming }

        fun onDismissed() = _stage.update { SosStage.Idle }

        fun onConfirmed(bookingId: UUID?) {
            if (_stage.value == SosStage.Sending) return
            _stage.update { SosStage.Sending }
            viewModelScope.launch {
                _stage.update {
                    when (val outcome = source.send(bookingId)) {
                        SosOutcome.Sent -> SosStage.Sent
                        SosOutcome.PermissionDenied ->
                            SosStage.Failed("Allow location for Lady Go so the safety team can see where you are, or call 112.")
                        SosOutcome.NoLocation -> SosStage.Failed("We could not find your location. Please call 112.")
                        is SosOutcome.Failed -> SosStage.Failed("${outcome.message} Please call 112.")
                    }
                }
            }
        }
    }
