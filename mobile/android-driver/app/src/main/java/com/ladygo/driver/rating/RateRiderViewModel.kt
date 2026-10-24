package com.ladygo.driver.rating

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ladygo.driver.auth.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

sealed interface RateResult {
    /** Saved. The sheet shows a brief thanks and closes itself. */
    data object Thanks : RateResult

    data object AlreadyRated : RateResult

    data class Rejected(
        val message: String,
    ) : RateResult

    /** Offers Retry or Skip. */
    data object NetworkFailure : RateResult

    /** The sheet has nothing more to ask for: only Close is left. */
    val isFinal: Boolean get() = this !is NetworkFailure
}

data class RateRiderUiState(
    val bookingId: UUID,
    /** 0 means no star chosen yet; Submit stays disabled. */
    val score: Int = 0,
    val tags: Set<RiderRatingTag> = emptySet(),
    val isSubmitting: Boolean = false,
    val result: RateResult? = null,
)

/** A rating the server accepted, so a ride list can update its row without a reload. */
data class RiderRated(
    val bookingId: UUID,
    val score: Int,
)

/**
 * The "rate the rider" prompt, activity-scoped so it outlives the screen the driver was on when she pressed Done.
 * It is purely additive: nothing waits for it, and [onSkip] (also the sheet's dismiss) always closes it at once.
 * One prompt at a time; a second Submit while one is in flight is ignored.
 */
@HiltViewModel
class RateRiderViewModel
    @Inject
    constructor(
        private val repository: RatingRepository,
        sessionManager: SessionManager,
    ) : ViewModel() {
        private val _prompt = MutableStateFlow<RateRiderUiState?>(null)
        val prompt: StateFlow<RateRiderUiState?> = _prompt.asStateFlow()

        private val _rated = MutableSharedFlow<RiderRated>(extraBufferCapacity = RATED_BUFFER)
        val rated: SharedFlow<RiderRated> = _rated.asSharedFlow()

        private val _alreadyRated = MutableSharedFlow<UUID>(extraBufferCapacity = RATED_BUFFER)

        /** The server said (409) this ride was already rated, so a ride list can hide its Rate button. */
        val alreadyRated: SharedFlow<UUID> = _alreadyRated.asSharedFlow()

        /** Bumped whenever the prompt is opened or closed: a late answer for an earlier prompt must not touch a newer one. */
        private var generation = 0

        init {
            // A prompt must never outlive the session: the next driver to sign in would be shown the last one's ride.
            viewModelScope.launch { sessionManager.isLoggedIn.collect { loggedIn -> if (!loggedIn) onSkip() } }
        }

        /** Opens the prompt for [bookingId]; asking again for the ride already being rated changes nothing. */
        fun offer(bookingId: UUID) {
            if (_prompt.value?.bookingId == bookingId) return
            generation++
            _prompt.value = RateRiderUiState(bookingId)
        }

        fun onScore(score: Int) {
            if (score !in 1..MAX_SCORE) return
            editIfOpen { it.copy(score = score, result = null) }
        }

        fun onToggleTag(tag: RiderRatingTag) {
            editIfOpen { it.copy(tags = if (tag in it.tags) it.tags - tag else it.tags + tag, result = null) }
        }

        fun onSubmit() {
            val current = _prompt.value ?: return
            if (current.isSubmitting || current.score !in 1..MAX_SCORE || current.result?.isFinal == true) return
            val startedAt = generation
            _prompt.value = current.copy(isSubmitting = true, result = null)
            viewModelScope.launch {
                val outcome = repository.rateRider(current.bookingId, current.score, current.tags)
                if (outcome is RateRiderOutcome.Success) _rated.tryEmit(RiderRated(current.bookingId, current.score))
                if (outcome is RateRiderOutcome.AlreadyRated) _alreadyRated.tryEmit(current.bookingId)
                if (startedAt != generation) return@launch // skipped or replaced while in flight
                val result =
                    when (outcome) {
                        RateRiderOutcome.Success -> RateResult.Thanks
                        RateRiderOutcome.AlreadyRated -> RateResult.AlreadyRated
                        is RateRiderOutcome.Rejected -> RateResult.Rejected(outcome.message)
                        RateRiderOutcome.NetworkFailure -> RateResult.NetworkFailure
                    }
                _prompt.update { it?.copy(isSubmitting = false, result = result) }
                if (result == RateResult.Thanks) {
                    delay(THANKS_MILLIS)
                    if (startedAt == generation) onSkip()
                }
            }
        }

        /** Skip, Close, and swipe-away all land here. */
        fun onSkip() {
            generation++
            _prompt.value = null
        }

        private fun editIfOpen(change: (RateRiderUiState) -> RateRiderUiState) {
            _prompt.update { state -> state?.takeUnless { it.isSubmitting || it.result?.isFinal == true }?.let(change) ?: state }
        }

        private companion object {
            const val MAX_SCORE = 5
            const val RATED_BUFFER = 4
            const val THANKS_MILLIS = 1_500L
        }
    }
