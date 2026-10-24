package com.ladygo.customer.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ladygo.customer.auth.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val NAME_MAX_LENGTH = 120

data class OnboardingUiState(
    val isLoading: Boolean = true,
    /** `null` while loading. */
    val state: OnboardingState? = null,
    val nameInput: String = "",
    val isSubmitting: Boolean = false,
    val error: String? = null,
) {
    /** The only way past this gate - a server-confirmed [OnboardingState.Ready], never a client guess. */
    val isReady: Boolean get() = state == OnboardingState.Ready
}

@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor(
        private val repository: OnboardingRepository,
        private val authRepository: AuthRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(OnboardingUiState())
        val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

        init {
            refresh()
        }

        fun refresh(keepError: Boolean = false) {
            _uiState.update { it.copy(isLoading = true, error = if (keepError) it.error else null) }
            viewModelScope.launch {
                val state = repository.fetchState()
                _uiState.update { it.copy(isLoading = false, state = state) }
            }
        }

        fun onNameChanged(name: String) = _uiState.update { it.copy(nameInput = name.take(NAME_MAX_LENGTH), error = null) }

        fun onSaveName() {
            val current = _uiState.value
            val name = current.nameInput.trim()
            if (name.isEmpty() || current.isSubmitting) return
            _uiState.update { it.copy(isSubmitting = true, error = null) }
            viewModelScope.launch {
                when (val outcome = repository.saveName(name)) {
                    OnboardingStepOutcome.Done -> {
                        _uiState.update { it.copy(isSubmitting = false) }
                        refresh() // the server decides what comes next (the declaration, or ready)
                    }
                    is OnboardingStepOutcome.Rejected -> fail(outcome.message)
                    is OnboardingStepOutcome.NetworkFailure -> fail(outcome.message)
                }
            }
        }

        fun onAcceptDeclaration() {
            val current = _uiState.value
            val declaration = current.state as? OnboardingState.NeedsDeclaration ?: return
            if (current.isSubmitting) return
            _uiState.update { it.copy(isSubmitting = true, error = null) }
            viewModelScope.launch {
                when (val outcome = repository.acceptDeclaration(declaration.version)) {
                    OnboardingStepOutcome.Done -> {
                        _uiState.update { it.copy(isSubmitting = false) }
                        refresh()
                    }
                    // A rejection can mean the declaration was re-published since this screen loaded: reload
                    // the current version/text so the next tap can't resend the same stale version forever.
                    is OnboardingStepOutcome.Rejected -> {
                        fail(outcome.message)
                        refresh(keepError = true)
                    }
                    is OnboardingStepOutcome.NetworkFailure -> fail(outcome.message)
                }
            }
        }

        fun onLogout() {
            viewModelScope.launch { authRepository.logout() }
        }

        private fun fail(message: String) = _uiState.update { it.copy(isSubmitting = false, error = message) }
    }
