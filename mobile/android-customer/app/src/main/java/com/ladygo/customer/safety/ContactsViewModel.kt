package com.ladygo.customer.safety

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ladygo.customer.auth.AuthValidation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

enum class ContactFieldError { NAME, PHONE }

data class ContactsUiState(
    val contacts: List<TrustedContact> = emptyList(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val name: String = "",
    val phone: String = "",
    val relationship: String = "",
    val fieldError: ContactFieldError? = null,
    val error: String? = null,
) {
    val canAddMore: Boolean get() = contacts.size < ContactsViewModel.MAX_CONTACTS
}

/** The people who are told where she is if she raises an SOS. They never need the app or an account. */
@HiltViewModel
class ContactsViewModel
    @Inject
    constructor(
        private val source: SafetySource,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(ContactsUiState())
        val uiState: StateFlow<ContactsUiState> = _uiState.asStateFlow()

        init {
            refresh()
        }

        fun refresh() {
            viewModelScope.launch {
                when (val result = source.contacts()) {
                    is SafetyResult.Ok -> _uiState.update { it.copy(contacts = result.value, loading = false, error = null) }
                    is SafetyResult.Failed -> _uiState.update { it.copy(loading = false, error = result.message) }
                }
            }
        }

        fun onNameChange(value: String) = _uiState.update { it.copy(name = value.take(MAX_NAME), fieldError = null) }

        fun onPhoneChange(value: String) = _uiState.update { it.copy(phone = value.take(MAX_PHONE), fieldError = null) }

        fun onRelationshipChange(value: String) = _uiState.update { it.copy(relationship = value.take(MAX_RELATIONSHIP)) }

        fun onAdd() {
            val state = _uiState.value
            if (state.saving) return
            val phone = normalizePhone(state.phone)
            val problem =
                when {
                    state.name.isBlank() -> ContactFieldError.NAME
                    phone == null -> ContactFieldError.PHONE
                    else -> null
                }
            if (problem != null || phone == null) {
                _uiState.update { it.copy(fieldError = problem) }
                return
            }
            _uiState.update { it.copy(saving = true, error = null) }
            viewModelScope.launch {
                when (val result = source.addContact(state.name.trim(), phone, state.relationship.trim().ifEmpty { null })) {
                    is SafetyResult.Ok ->
                        _uiState.update {
                            it.copy(
                                contacts = it.contacts + result.value,
                                saving = false,
                                name = "",
                                phone = "",
                                relationship = "",
                            )
                        }
                    is SafetyResult.Failed -> _uiState.update { it.copy(saving = false, error = result.message) }
                }
            }
        }

        fun onDelete(id: UUID) {
            viewModelScope.launch {
                when (val result = source.deleteContact(id)) {
                    is SafetyResult.Ok -> _uiState.update { s -> s.copy(contacts = s.contacts.filterNot { it.id == id }, error = null) }
                    is SafetyResult.Failed -> _uiState.update { it.copy(error = result.message) }
                }
            }
        }

        companion object {
            const val MAX_CONTACTS = 5
            const val MAX_NAME = 120
            const val MAX_PHONE = 20
            const val MAX_RELATIONSHIP = 50
            private const val LOCAL_NUMBER_LENGTH = 10

            /** A 10-digit Indian number gets +91; anything else must already carry its country code. Null if invalid. */
            fun normalizePhone(raw: String): String? {
                val cleaned = raw.filter { it.isDigit() || it == '+' }
                val candidate = if (cleaned.length == LOCAL_NUMBER_LENGTH && cleaned.all { it.isDigit() }) "+91$cleaned" else cleaned
                return candidate.takeIf { AuthValidation.isPhone(it) }
            }
        }
    }
