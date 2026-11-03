package com.ladygo.driver.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ladygo.driver.auth.AuthRepository
import com.ladygo.driver.auth.BlockedReason
import com.ladygo.driver.auth.RequestOtpOutcome
import com.ladygo.driver.auth.VerifyOtpOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LoginStep { PHONE_ENTRY, CODE_ENTRY }

data class LoginUiState(
    val step: LoginStep = LoginStep.PHONE_ENTRY,
    val phone: String = "",
    val email: String = "",
    val code: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val loggedIn: Boolean = false,
    /** Set when the code was right but this driver may not use the app yet; the screen shows why and how to reach us. */
    val blocked: BlockedReason? = null,
) {
    val canRequestCode: Boolean get() = phone.isNotBlank() && email.isNotBlank() && !isLoading
}

@HiltViewModel
class LoginViewModel
    @Inject
    constructor(
        private val authRepository: AuthRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(LoginUiState())
        val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

        fun onPhoneChanged(phone: String) {
            _uiState.value = _uiState.value.copy(phone = phone, error = null)
        }

        fun onEmailChanged(email: String) {
            _uiState.value = _uiState.value.copy(email = email.trim(), error = null)
        }

        /** Leaves the "cannot log in yet" screen for a fresh login. */
        fun onDismissBlocked() {
            _uiState.value = _uiState.value.copy(step = LoginStep.PHONE_ENTRY, code = "", blocked = null, error = null)
        }

        fun onCodeChanged(code: String) {
            _uiState.value = _uiState.value.copy(code = code, error = null)
        }

        fun onBackToPhoneEntry() {
            _uiState.value = _uiState.value.copy(step = LoginStep.PHONE_ENTRY, code = "", error = null)
        }

        fun onRequestOtp() {
            val state = _uiState.value
            if (!state.canRequestCode) return
            _uiState.value = state.copy(isLoading = true, error = null)
            viewModelScope.launch {
                when (val outcome = authRepository.requestOtp(state.phone, state.email)) {
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
