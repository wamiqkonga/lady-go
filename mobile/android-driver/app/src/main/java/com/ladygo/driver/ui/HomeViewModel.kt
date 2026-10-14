package com.ladygo.driver.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ladygo.driver.BuildConfig
import com.ladygo.driver.auth.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val apiBaseUrl: String = BuildConfig.API_BASE_URL,
    val phaseNote: String = "Skeleton build. Going online and offers arrive in a later slice.",
)

/** Placeholder home screen. Going-online/offers/ride-lifecycle UI (slice 2) follow the roadmap.
 *  Logging out flips [com.ladygo.driver.auth.SessionManager.isLoggedIn] - the nav host's own
 *  top-level observer is what navigates back to Login, not this ViewModel. */
@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        private val authRepository: AuthRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(HomeUiState())
        val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

        fun onLogout() {
            viewModelScope.launch {
                authRepository.logout()
            }
        }
    }
