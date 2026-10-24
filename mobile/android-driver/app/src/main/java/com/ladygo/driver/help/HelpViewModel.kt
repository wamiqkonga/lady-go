package com.ladygo.driver.help

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ladygo.driver.account.AccountRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HelpUiState(
    val isLoading: Boolean = true,
    /** Null until the support details load (or when they could not). */
    val contact: SupportContact? = null,
    /** The support details could not be loaded; Call 112 and the diagnostics still work. */
    val error: String? = null,
    /** The anonymised driver reference, once known. */
    val driverReference: String? = null,
)

/** Help: support numbers from server config, plus the diagnostics text. Call 112 needs nothing from here. */
@HiltViewModel
class HelpViewModel
    @Inject
    constructor(
        private val supportRepository: SupportRepository,
        private val accountRepository: AccountRepository,
        private val appInfoProvider: AppInfoProvider,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(HelpUiState())
        val uiState: StateFlow<HelpUiState> = _uiState.asStateFlow()

        init {
            loadContact()
            viewModelScope.launch {
                val reference = Diagnostics.reference(accountRepository.fetchDriverId())
                _uiState.value = _uiState.value.copy(driverReference = reference)
            }
        }

        fun onRetry() {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            loadContact()
        }

        /** The text for the clipboard, built from what is known right now. */
        fun diagnostics(): String = Diagnostics.build(appInfoProvider.current(), _uiState.value.driverReference ?: Diagnostics.UNAVAILABLE)

        private fun loadContact() {
            viewModelScope.launch {
                when (val outcome = supportRepository.fetch()) {
                    is SupportOutcome.Success ->
                        _uiState.value = _uiState.value.copy(isLoading = false, contact = outcome.contact, error = null)
                    is SupportOutcome.Failed ->
                        _uiState.value = _uiState.value.copy(isLoading = false, error = outcome.message)
                }
            }
        }
    }
