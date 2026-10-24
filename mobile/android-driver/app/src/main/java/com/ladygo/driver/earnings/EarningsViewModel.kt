package com.ladygo.driver.earnings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ladygo.driver.api.generated.models.DriverEarningsResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

data class EarningsUiState(
    val week: WeekChoice = WeekChoice.THIS_WEEK,
    val range: DateRange,
    /** True only while there is nothing to show yet (first load, or a week switch). */
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val earnings: DriverEarningsResponse? = null,
    /** A failure with nothing on screen: shown as a full error state with Retry. */
    val error: String? = null,
    /** A failed refresh while [earnings] is still on screen: shown as a banner, the numbers stay. */
    val refreshError: String? = null,
)

/**
 * One week of earnings at a time. The selected week's range is recomputed from the device date on every
 * load, so a refresh after midnight or on a new Monday moves "this week" forward. Switching weeks drops the
 * old week's numbers (they would be shown under the wrong heading); a failed pull-to-refresh keeps them.
 */
@HiltViewModel
class EarningsViewModel
    @Inject
    constructor(
        private val repository: EarningsRepository,
        private val clock: Clock,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(EarningsUiState(range = rangeFor(WeekChoice.THIS_WEEK)))
        val uiState: StateFlow<EarningsUiState> = _uiState.asStateFlow()
        private var loadJob: Job? = null

        init {
            load()
        }

        fun onSelectWeek(week: WeekChoice) {
            if (week == _uiState.value.week) return
            _uiState.value =
                EarningsUiState(week = week, range = rangeFor(week), isLoading = true)
            load()
        }

        fun onRetry() {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, range = rangeFor(_uiState.value.week))
            load()
        }

        fun onRefresh() {
            val current = _uiState.value
            if (current.earnings == null) return onRetry()
            _uiState.value = current.copy(isRefreshing = true, refreshError = null, range = rangeFor(current.week))
            load()
        }

        /** The app came back to the foreground: quietly re-read, keeping what is on screen. Skipped while a load is running. */
        fun onResume() {
            val state = _uiState.value
            if (state.isLoading || state.isRefreshing) return
            if (state.earnings != null) onRefresh()
        }

        private fun load() {
            loadJob?.cancel()
            val range = _uiState.value.range
            loadJob =
                viewModelScope.launch {
                    when (val outcome = repository.fetch(range)) {
                        is EarningsOutcome.Success ->
                            _uiState.value =
                                _uiState.value.copy(
                                    isLoading = false,
                                    isRefreshing = false,
                                    earnings = outcome.earnings,
                                    error = null,
                                    refreshError = null,
                                )
                        is EarningsOutcome.Failed -> {
                            val hasData = _uiState.value.earnings != null
                            _uiState.value =
                                _uiState.value.copy(
                                    isLoading = false,
                                    isRefreshing = false,
                                    error = if (hasData) null else outcome.message,
                                    refreshError = if (hasData) outcome.message else null,
                                )
                        }
                    }
                }
        }

        private fun rangeFor(week: WeekChoice): DateRange = weekRange(LocalDate.now(clock), week)
    }
