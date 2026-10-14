package com.ladygo.driver.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ladygo.driver.api.generated.models.DriverProfileResponse.VerificationStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GateUiState(
    val isLoading: Boolean = true,
    val status: OnboardingStatus? = null,
)

/** True only for a server-confirmed APPROVED status - every other [OnboardingStatus] (including
 *  [OnboardingStatus.NotStarted] and [OnboardingStatus.LoadError]) keeps the driver on the gate
 *  screen. This is the single place that decides reachability of the home screen - see §3 of
 *  docs/product/driver-android-design.md ("server is the only source of truth"). */
fun GateUiState.isApproved(): Boolean {
    val known = status as? OnboardingStatus.Known
    return known?.verificationStatus == VerificationStatus.APPROVED
}

@HiltViewModel
class OnboardingGateViewModel
    @Inject
    constructor(
        private val onboardingRepository: OnboardingRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(GateUiState())
        val uiState: StateFlow<GateUiState> = _uiState.asStateFlow()

        init {
            refresh()
        }

        fun refresh() {
            _uiState.value = _uiState.value.copy(isLoading = true)
            viewModelScope.launch {
                val status = onboardingRepository.fetchStatus()
                _uiState.value = GateUiState(isLoading = false, status = status)
            }
        }
    }
