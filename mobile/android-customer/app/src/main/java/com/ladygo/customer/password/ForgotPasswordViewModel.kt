package com.ladygo.customer.password

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ladygo.customer.auth.AuthOutcome
import com.ladygo.customer.auth.AuthRepository
import com.ladygo.customer.auth.AuthValidation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ForgotStep { EMAIL, NEW_PASSWORD, DONE }

data class ForgotPasswordUiState(
    val step: ForgotStep = ForgotStep.EMAIL,
    val email: String = "",
    val code: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    val emailError: Boolean get() = email.isNotBlank() && !AuthValidation.isEmail(email)

    val passwordError: Boolean get() = password.isNotEmpty() && !AuthValidation.isStrongPassword(password)

    val confirmError: Boolean get() = confirmPassword.isNotEmpty() && confirmPassword != password

    val canReset: Boolean
        get() =
            AuthValidation.isCode(code) && AuthValidation.isStrongPassword(password) && password == confirmPassword && !isLoading
}

/** Forgot password: email first (a code is emailed if the account exists), then the code and a new password. */
@HiltViewModel
class ForgotPasswordViewModel
    @Inject
    constructor(
        private val authRepository: AuthRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(ForgotPasswordUiState())
        val uiState: StateFlow<ForgotPasswordUiState> = _uiState.asStateFlow()

        fun onEmailChanged(value: String) = _uiState.update { it.copy(email = value.trim(), error = null) }

        fun onCodeChanged(value: String) =
            _uiState.update {
                it.copy(code = value.filter(Char::isDigit).take(AuthValidation.CODE_LENGTH), error = null)
            }

        fun onPasswordChanged(value: String) = _uiState.update { it.copy(password = value, error = null) }

        fun onConfirmPasswordChanged(value: String) = _uiState.update { it.copy(confirmPassword = value, error = null) }

        fun onBackToEmail() = _uiState.update { it.copy(step = ForgotStep.EMAIL, code = "", error = null) }

        /** The server answers the same whether or not the email has an account, so the next step always follows. */
        fun onSendCode() {
            val state = _uiState.value
            if (!AuthValidation.isEmail(state.email) || state.isLoading) return
            _uiState.update { it.copy(isLoading = true, error = null) }
            viewModelScope.launch {
                when (val outcome = authRepository.requestPasswordReset(state.email)) {
                    AuthOutcome.Success -> _uiState.update { it.copy(isLoading = false, step = ForgotStep.NEW_PASSWORD) }
                    is AuthOutcome.Failed -> _uiState.update { it.copy(isLoading = false, error = outcome.message) }
                }
            }
        }

        fun onResetPassword() {
            val state = _uiState.value
            if (!state.canReset) return
            _uiState.update { it.copy(isLoading = true, error = null) }
            viewModelScope.launch {
                when (val outcome = authRepository.resetPassword(state.email, state.code, state.password)) {
                    AuthOutcome.Success -> _uiState.update { it.copy(isLoading = false, step = ForgotStep.DONE) }
                    is AuthOutcome.Failed -> _uiState.update { it.copy(isLoading = false, error = outcome.message) }
                }
            }
        }
    }
