package com.ladygo.customer.ui

import androidx.lifecycle.ViewModel
import com.ladygo.customer.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class HomeUiState(
    val apiBaseUrl: String,
    val phaseNote: String = "Skeleton build. Features arrive in later development phases.",
)

/** Placeholder MVVM view model. Real screens (auth, booking, ...) follow the roadmap. */
class HomeViewModel @JvmOverloads constructor(
    apiBaseUrl: String = BuildConfig.API_BASE_URL,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState(apiBaseUrl = apiBaseUrl))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
}
