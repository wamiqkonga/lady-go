package com.ladygo.driver.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ladygo.driver.auth.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MeUiState(
    /** True only while there is nothing to show yet. */
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val overview: AccountOverview? = null,
    /** Every section failed with nothing on screen: a full error state with Retry. */
    val error: String? = null,
    /** A refresh failed (fully or in part) while older data is still on screen: a banner, the data stays. */
    val refreshError: String? = null,
)

/**
 * The Me tab: rating, documents and vehicle (read-only) plus Log out, which is the same call Home's button makes
 * (the nav host sees the session end and goes to Login). A failed refresh never wipes what is on screen.
 */
@HiltViewModel
class MeViewModel
    @Inject
    constructor(
        private val repository: AccountRepository,
        private val authRepository: AuthRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(MeUiState())
        val uiState: StateFlow<MeUiState> = _uiState.asStateFlow()
        private var loadJob: Job? = null

        init {
            load()
        }

        fun onRetry() {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            load()
        }

        fun onRefresh() {
            val current = _uiState.value
            if (current.overview == null) return onRetry()
            _uiState.value = current.copy(isRefreshing = true, refreshError = null)
            load()
        }

        /** The app came back to the foreground: quietly re-read, keeping what is on screen. Skipped while a load is running. */
        fun onResume() {
            val state = _uiState.value
            if (state.isLoading || state.isRefreshing) return
            if (state.overview != null) onRefresh()
        }

        fun onLogout() {
            viewModelScope.launch { authRepository.logout() }
        }

        private fun load() {
            loadJob?.cancel()
            loadJob =
                viewModelScope.launch {
                    val fresh = repository.fetchOverview()
                    val previous = _uiState.value.overview
                    _uiState.value =
                        when {
                            // Nothing at all came back: keep older data if there is some, else show the error state.
                            fresh.allFailed && previous != null ->
                                _uiState.value.copy(isLoading = false, isRefreshing = false, refreshError = fresh.firstError)
                            fresh.allFailed ->
                                _uiState.value.copy(isLoading = false, isRefreshing = false, error = fresh.firstError)
                            else ->
                                MeUiState(
                                    isLoading = false,
                                    overview = fresh.keepingOlderSections(previous),
                                    refreshError = fresh.firstError.takeIf { previous != null },
                                )
                        }
                }
        }
    }

/** A section that failed this time falls back to what was loaded before (if anything); the banner still says refresh failed. */
private fun AccountOverview.keepingOlderSections(previous: AccountOverview?): AccountOverview {
    if (previous == null) return this
    return AccountOverview(
        rating = if (rating is Part.Failed && previous.rating is Part.Loaded) previous.rating else rating,
        documents = if (documents is Part.Failed && previous.documents is Part.Loaded) previous.documents else documents,
        vehicle = if (vehicle is Part.Failed && previous.vehicle is Part.Loaded) previous.vehicle else vehicle,
    )
}
