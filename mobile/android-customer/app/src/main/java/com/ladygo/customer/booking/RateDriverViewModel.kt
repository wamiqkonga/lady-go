package com.ladygo.customer.booking

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ladygo.customer.api.generated.models.SubmitRatingRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

enum class RatingStatus { IDLE, SUBMITTING, DONE, FAILED }

data class RatingUiState(
    val status: RatingStatus = RatingStatus.IDLE,
)

/** Rates the driver of the ride on screen. The booking id comes from the same nav argument as the track screen. */
@HiltViewModel
class RateDriverViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val source: RatingSource,
    ) : ViewModel() {
        private val bookingId: UUID = UUID.fromString(checkNotNull(savedStateHandle.get<String>(TrackBookingViewModel.BOOKING_ID_ARG)))

        private val _uiState = MutableStateFlow(RatingUiState())
        val uiState: StateFlow<RatingUiState> = _uiState.asStateFlow()

        fun onSubmit(
            score: Int,
            tags: List<SubmitRatingRequest.Tags>,
            comment: String,
        ) {
            if (score !in MIN_SCORE..MAX_SCORE) return
            if (_uiState.value.status == RatingStatus.SUBMITTING || _uiState.value.status == RatingStatus.DONE) return
            _uiState.update { RatingUiState(RatingStatus.SUBMITTING) }
            viewModelScope.launch {
                val status =
                    when (source.rateDriver(bookingId, score, tags, comment.take(MAX_COMMENT_LENGTH))) {
                        RatingOutcome.Submitted, RatingOutcome.AlreadyRated -> RatingStatus.DONE
                        RatingOutcome.Failed -> RatingStatus.FAILED
                    }
                _uiState.update { RatingUiState(status) }
            }
        }

        companion object {
            const val MIN_SCORE = 1
            const val MAX_SCORE = 5
            const val MAX_COMMENT_LENGTH = 500
        }
    }
