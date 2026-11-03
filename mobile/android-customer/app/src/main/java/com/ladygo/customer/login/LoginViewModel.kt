package com.ladygo.customer.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ladygo.customer.auth.AuthOutcome
import com.ladygo.customer.auth.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val identifier: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val loggedIn: Boolean = false,
) {
    val canSubmit: Boolean get() = identifier.isNotBlank() && password.isNotEmpty() && !isLoading
}

/** Sign in with email or phone plus password. */
@HiltViewModel
class LoginViewModel
    @Inject
    constructor(
        private val authRepository: AuthRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(LoginUiState())
        val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

        fun onIdentifierChanged(value: String) = _uiState.update { it.copy(identifier = value, error = null) }

        fun onPasswordChanged(value: String) = _uiState.update { it.copy(password = value, error = null) }

        fun onLogin() {
            val state = _uiState.value
            if (!state.canSubmit) return
            _uiState.update { it.copy(isLoading = true, error = null) }
            viewModelScope.launch {
                when (val outcome = authRepository.login(state.identifier.trim(), state.password)) {
                    AuthOutcome.Success -> _uiState.update { it.copy(isLoading = false, loggedIn = true) }
                    is AuthOutcome.Failed -> _uiState.update { it.copy(isLoading = false, error = outcome.message) }
                }
            }
        }
    }
