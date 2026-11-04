package com.ladygo.customer.register

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

enum class RegisterStep { DETAILS, CODE_AND_PASSWORD }

data class RegisterUiState(
    val step: RegisterStep = RegisterStep.DETAILS,
    val phone: String = "",
    val email: String = "",
    val code: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val registered: Boolean = false,
) {
    val detailsValid: Boolean get() = AuthValidation.isPhone(phone) && AuthValidation.isEmail(email)

    val phoneError: Boolean get() = phone.isNotBlank() && !AuthValidation.isPhone(phone)

    val emailError: Boolean get() = email.isNotBlank() && !AuthValidation.isEmail(email)

    val passwordError: Boolean get() = password.isNotEmpty() && !AuthValidation.isStrongPassword(password)

    val confirmError: Boolean get() = confirmPassword.isNotEmpty() && confirmPassword != password

    val canCreate: Boolean
        get() =
            AuthValidation.isCode(code) && AuthValidation.isStrongPassword(password) && password == confirmPassword && !isLoading
}

/** Sign-up: phone and email first (a code is emailed), then the code and a password. */
@HiltViewModel
class RegisterViewModel
    @Inject
    constructor(
        private val authRepository: AuthRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(RegisterUiState())
        val uiState: StateFlow<RegisterUiState> = _uiState.asStateFlow()

        fun onPhoneChanged(value: String) = _uiState.update { it.copy(phone = value.filterNot(Char::isWhitespace), error = null) }

        fun onEmailChanged(value: String) = _uiState.update { it.copy(email = value.trim(), error = null) }

        fun onCodeChanged(value: String) =
            _uiState.update {
                it.copy(code = value.filter(Char::isDigit).take(AuthValidation.CODE_LENGTH), error = null)
            }

        fun onPasswordChanged(value: String) = _uiState.update { it.copy(password = value, error = null) }

        fun onConfirmPasswordChanged(value: String) = _uiState.update { it.copy(confirmPassword = value, error = null) }

        fun onBackToDetails() = _uiState.update { it.copy(step = RegisterStep.DETAILS, code = "", error = null) }

        fun onSendCode() {
            val state = _uiState.value
            if (!state.detailsValid || state.isLoading) return
            _uiState.update { it.copy(isLoading = true, error = null) }
            viewModelScope.launch {
                when (val outcome = authRepository.requestRegistrationOtp(state.phone, state.email)) {
                    AuthOutcome.Success -> _uiState.update { it.copy(isLoading = false, step = RegisterStep.CODE_AND_PASSWORD) }
                    is AuthOutcome.Failed -> _uiState.update { it.copy(isLoading = false, error = outcome.message) }
                }
            }
        }

        fun onCreateAccount() {
            val state = _uiState.value
            if (!state.canCreate) return
            _uiState.update { it.copy(isLoading = true, error = null) }
            viewModelScope.launch {
                when (val outcome = authRepository.register(state.phone, state.email, state.code, state.password)) {
                    AuthOutcome.Success -> _uiState.update { it.copy(isLoading = false, registered = true) }
                    is AuthOutcome.Failed -> _uiState.update { it.copy(isLoading = false, error = outcome.message) }
                }
            }
        }
    }
