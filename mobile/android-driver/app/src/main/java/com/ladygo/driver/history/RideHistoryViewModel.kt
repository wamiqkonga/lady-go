package com.ladygo.driver.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ladygo.driver.network.PagedList
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class RideHistoryUiState(
    val items: List<RideHistoryItem> = emptyList(),
    /** True only for the very first page, while nothing is on screen. */
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    /** A failure of the first page with nothing on screen: a full error state with Retry. */
    val error: String? = null,
    /** A failed refresh while rows are on screen: a banner, the rows stay. */
    val refreshError: String? = null,
    /** A failed next page: a small retry row at the end of the list. */
    val loadMoreError: String? = null,
)

private val RideHistoryUiState.isBusy: Boolean get() = isLoading || isRefreshing || isLoadingMore

/**
 * Ride history, newest first, 20 per page. Pages are appended without duplicates ([PagedList]); a refresh
 * restarts from page 0 and replaces the rows only when it succeeds. At most one request is in flight: a
 * refresh cancels a running next-page load, and a next-page request is ignored while anything else is loading.
 * The detail screen reads its row from [item] - there is no per-ride API call.
 */
@HiltViewModel
class RideHistoryViewModel
    @Inject
    constructor(
        private val repository: RideHistoryRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(RideHistoryUiState())
        val uiState: StateFlow<RideHistoryUiState> = _uiState.asStateFlow()
        private var paged = PagedList<RideHistoryItem, UUID>(keyOf = { it.bookingId })
        private var loadJob: Job? = null

        init {
            loadFirstPage()
        }

        fun item(bookingId: UUID): RideHistoryItem? = _uiState.value.items.firstOrNull { it.bookingId == bookingId }

        /** The server accepted this driver's rating of [bookingId]: show it on the row now, no reload. */
        fun onRated(
            bookingId: UUID,
            score: Int,
        ) {
            if (paged.items.none { it.bookingId == bookingId }) return
            paged = paged.copy(items = paged.items.map { if (it.bookingId == bookingId) it.copy(myRating = score) else it })
            _uiState.value = _uiState.value.copy(items = paged.items)
        }

        fun onRetry() {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            loadFirstPage()
        }

        fun onRefresh() {
            if (_uiState.value.items.isEmpty()) return onRetry()
            loadJob?.cancel()
            _uiState.value = _uiState.value.copy(isRefreshing = true, isLoadingMore = false, refreshError = null, loadMoreError = null)
            loadFirstPage()
        }

        /** The app came back to the foreground (e.g. after finishing a ride): quietly re-read the first page. */
        fun onResume() {
            val state = _uiState.value
            if (state.isBusy || state.items.isEmpty()) return
            onRefresh()
        }

        /** Called as the driver nears the end of the list, and by the retry row after a failed next page. */
        fun onLoadMore() {
            val state = _uiState.value
            if (!state.hasMore || state.isBusy) return
            _uiState.value = state.copy(isLoadingMore = true, loadMoreError = null)
            loadJob =
                viewModelScope.launch {
                    when (val outcome = repository.fetchPage(paged.nextPage)) {
                        is RideHistoryOutcome.Success -> {
                            paged = paged.append(outcome.items, outcome.hasMore)
                            _uiState.value = _uiState.value.copy(items = paged.items, hasMore = paged.hasMore, isLoadingMore = false)
                        }
                        is RideHistoryOutcome.Failed ->
                            _uiState.value = _uiState.value.copy(isLoadingMore = false, loadMoreError = outcome.message)
                    }
                }
        }

        private fun loadFirstPage() {
            loadJob?.cancel()
            loadJob =
                viewModelScope.launch {
                    when (val outcome = repository.fetchPage(0)) {
                        is RideHistoryOutcome.Success -> {
                            paged = paged.restart(outcome.items, outcome.hasMore)
                            _uiState.value =
                                RideHistoryUiState(items = paged.items, isLoading = false, hasMore = paged.hasMore)
                        }
                        is RideHistoryOutcome.Failed -> {
                            val hasRows = _uiState.value.items.isNotEmpty()
                            _uiState.value =
                                _uiState.value.copy(
                                    isLoading = false,
                                    isRefreshing = false,
                                    error = if (hasRows) null else outcome.message,
                                    refreshError = if (hasRows) outcome.message else null,
                                )
                        }
                    }
                }
        }
    }
