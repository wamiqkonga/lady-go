@file:Suppress("MatchingDeclarationName")

package com.ladygo.customer.safety.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ladygo.customer.R
import com.ladygo.customer.safety.ContactFieldError
import com.ladygo.customer.safety.ContactsUiState
import com.ladygo.customer.safety.ContactsViewModel
import com.ladygo.customer.safety.TrustedContact
import com.ladygo.customer.ui.components.BannerKind
import com.ladygo.customer.ui.components.InfoBanner
import com.ladygo.customer.ui.components.LadyGoCard
import com.ladygo.customer.ui.components.LadyGoScreen
import com.ladygo.customer.ui.components.LadyGoTextField
import com.ladygo.customer.ui.components.LoadingState
import com.ladygo.customer.ui.components.PrimaryButton
import com.ladygo.customer.ui.components.QuietButton
import com.ladygo.customer.ui.components.SectionLabel
import java.util.UUID

data class ContactsActions(
    val onBack: () -> Unit,
    val onName: (String) -> Unit,
    val onPhone: (String) -> Unit,
    val onRelationship: (String) -> Unit,
    val onAdd: () -> Unit,
    val onDelete: (UUID) -> Unit,
)

@Composable
fun ContactsScreen(
    onBack: () -> Unit,
    viewModel: ContactsViewModel = hiltViewModel(),
) {
    ContactsContent(
        state = viewModel.uiState.collectAsStateWithLifecycle().value,
        actions =
            ContactsActions(
                onBack = onBack,
                onName = viewModel::onNameChange,
                onPhone = viewModel::onPhoneChange,
                onRelationship = viewModel::onRelationshipChange,
                onAdd = viewModel::onAdd,
                onDelete = viewModel::onDelete,
            ),
    )
}

@Composable
fun ContactsContent(
    state: ContactsUiState,
    actions: ContactsActions,
) {
    LadyGoScreen(title = stringResource(R.string.contacts_title), onBack = actions.onBack) {
        Text(stringResource(R.string.contacts_intro), style = MaterialTheme.typography.bodyMedium)
        state.error?.let { InfoBanner(text = it, kind = BannerKind.ERROR, modifier = Modifier.testTag("contacts_error")) }
        if (state.loading) {
            LoadingState(message = stringResource(R.string.onboarding_loading))
            return@LadyGoScreen
        }
        if (state.contacts.isEmpty()) Text(stringResource(R.string.contacts_empty), modifier = Modifier.testTag("contacts_empty"))
        state.contacts.forEach { ContactRow(it, actions.onDelete) }
        if (state.canAddMore) {
            AddContactForm(state, actions)
        } else {
            InfoBanner(text = stringResource(R.string.contacts_limit_note), kind = BannerKind.INFO)
        }
    }
}

@Composable
private fun ContactRow(
    contact: TrustedContact,
    onDelete: (UUID) -> Unit,
) {
    LadyGoCard(modifier = Modifier.testTag("contact_${contact.name}")) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
                Text(contact.name, style = MaterialTheme.typography.titleMedium)
                Text(listOfNotNull(contact.relationship, contact.phone).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            }
            QuietButton(text = stringResource(R.string.contacts_remove_action), onClick = { onDelete(contact.id) })
        }
    }
}

@Composable
private fun AddContactForm(
    state: ContactsUiState,
    actions: ContactsActions,
) {
    SectionLabel(stringResource(R.string.contacts_add_title))
    LadyGoTextField(
        value = state.name,
        onValueChange = actions.onName,
        label = stringResource(R.string.contacts_name_label),
        isError = state.fieldError == ContactFieldError.NAME,
        helper = if (state.fieldError == ContactFieldError.NAME) stringResource(R.string.contacts_name_error) else null,
        modifier = Modifier.testTag("contact_name"),
    )
    LadyGoTextField(
        value = state.phone,
        onValueChange = actions.onPhone,
        label = stringResource(R.string.contacts_phone_label),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        isError = state.fieldError == ContactFieldError.PHONE,
        helper =
            if (state.fieldError == ContactFieldError.PHONE) {
                stringResource(R.string.contacts_phone_error)
            } else {
                stringResource(R.string.contacts_phone_helper)
            },
        modifier = Modifier.testTag("contact_phone"),
    )
    LadyGoTextField(
        value = state.relationship,
        onValueChange = actions.onRelationship,
        label = stringResource(R.string.contacts_relationship_label),
        modifier = Modifier.testTag("contact_relationship"),
    )
    PrimaryButton(
        text = stringResource(R.string.contacts_add_action),
        onClick = actions.onAdd,
        loading = state.saving,
        modifier = Modifier.testTag("contact_save"),
    )
}
