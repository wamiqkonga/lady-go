package com.ladygo.driver.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ladygo.driver.auth.AuthRepository
import com.ladygo.driver.auth.BlockedReason
import com.ladygo.driver.auth.PasswordLoginOutcome
import com.ladygo.driver.auth.RequestOtpOutcome
import com.ladygo.driver.auth.VerifyOtpOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LoginStep { PASSWORD_ENTRY, PHONE_ENTRY, CODE_ENTRY }

data class LoginUiState(
    val step: LoginStep = LoginStep.PHONE_ENTRY,
    val phone: String = "",
    val code: String = "",
    val identifier: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val loggedIn: Boolean = false,
    /** Set when the sign-in was right but this driver may not use the app yet; the screen shows why and how to reach us. */
    val blocked: BlockedReason? = null,
)

@HiltViewModel
class LoginViewModel
    @Inject
    constructor(
        private val authRepository: AuthRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(LoginUiState(step = LoginStep.PASSWORD_ENTRY))
        val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

        fun onPhoneChanged(phone: String) {
            _uiState.value = _uiState.value.copy(phone = phone, error = null)
        }

        fun onCodeChanged(code: String) {
            _uiState.value = _uiState.value.copy(code = code, error = null)
        }

        fun onIdentifierChanged(identifier: String) {
            _uiState.value = _uiState.value.copy(identifier = identifier, error = null)
        }

        fun onPasswordChanged(password: String) {
            _uiState.value = _uiState.value.copy(password = password, error = null)
        }

        /** Leaves the "cannot log in yet" screen for a fresh sign-in. */
        fun onDismissBlocked() {
            _uiState.value = _uiState.value.copy(step = LoginStep.PASSWORD_ENTRY, code = "", password = "", blocked = null, error = null)
        }

        fun onUseCodeInstead() {
            _uiState.value = _uiState.value.copy(step = LoginStep.PHONE_ENTRY, password = "", error = null)
        }

        fun onUsePasswordInstead() {
            _uiState.value = _uiState.value.copy(step = LoginStep.PASSWORD_ENTRY, code = "", error = null)
        }

        fun onPasswordLogin() {
            val state = _uiState.value
            if (state.identifier.isBlank() || state.password.isEmpty() || state.isLoading) return
            _uiState.value = state.copy(isLoading = true, error = null)
            viewModelScope.launch {
                when (val outcome = authRepository.loginWithPassword(state.identifier, state.password)) {
                    PasswordLoginOutcome.LoggedIn ->
                        _uiState.value = _uiState.value.copy(isLoading = false, password = "", loggedIn = true)
                    is PasswordLoginOutcome.Blocked ->
                        _uiState.value = _uiState.value.copy(isLoading = false, password = "", blocked = outcome.reason)
                    is PasswordLoginOutcome.Failed ->
                        _uiState.value = _uiState.value.copy(isLoading = false, password = "", error = outcome.message)
                }
            }
        }

        fun onBackToPhoneEntry() {
            _uiState.value = _uiState.value.copy(step = LoginStep.PHONE_ENTRY, code = "", error = null)
        }

        fun onRequestOtp() {
            val phone = _uiState.value.phone
            if (phone.isBlank() || _uiState.value.isLoading) return
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            viewModelScope.launch {
                when (val outcome = authRepository.requestOtp(phone)) {
                    RequestOtpOutcome.Sent ->
                        _uiState.value = _uiState.value.copy(isLoading = false, step = LoginStep.CODE_ENTRY)
                    is RequestOtpOutcome.Failed ->
                        _uiState.value = _uiState.value.copy(isLoading = false, error = outcome.message)
                }
            }
        }

        fun onVerifyOtp() {
            val state = _uiState.value
            if (state.code.isBlank() || state.isLoading) return
            _uiState.value = state.copy(isLoading = true, error = null)
            viewModelScope.launch {
                when (val outcome = authRepository.verifyOtp(state.phone, state.code)) {
                    VerifyOtpOutcome.LoggedIn ->
                        _uiState.value = _uiState.value.copy(isLoading = false, loggedIn = true)
                    is VerifyOtpOutcome.Blocked ->
                        _uiState.value = _uiState.value.copy(isLoading = false, blocked = outcome.reason)
                    is VerifyOtpOutcome.Failed ->
                        _uiState.value = _uiState.value.copy(isLoading = false, error = outcome.message)
                }
            }
        }
    }
