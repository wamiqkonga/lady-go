@file:Suppress("MatchingDeclarationName")

package com.ladygo.customer.safety.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ladygo.customer.R
import com.ladygo.customer.booking.ExternalIntents
import com.ladygo.customer.safety.SafetyUiState
import com.ladygo.customer.safety.SafetyViewModel
import com.ladygo.customer.safety.SosStage
import com.ladygo.customer.ui.components.BannerKind
import com.ladygo.customer.ui.components.EmergencyButton
import com.ladygo.customer.ui.components.InfoBanner
import com.ladygo.customer.ui.components.SecondaryButton

/** What the panel can do; the real screen wires these to [SafetyViewModel] and the phone's apps. */
data class SafetyActions(
    val onShare: () -> Unit,
    val onSos: () -> Unit,
    val onSosConfirm: () -> Unit,
    val onSosDismiss: () -> Unit,
    val onTextContacts: () -> Unit,
    val onCall112: () -> Unit,
)

/** Share trip and SOS, shown while a driver is coming or the trip is under way. */
@Composable
fun SafetyPanel(viewModel: SafetyViewModel = hiltViewModel()) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    val baseUrl = stringResource(R.string.trip_share_base_url)
    val shareMessage = stringResource(R.string.safety_share_message, baseUrl + "%s")
    val chooser = stringResource(R.string.safety_share_chooser)
    val smsText = stringResource(R.string.safety_sos_sms, "%s")

    LaunchedEffect(state.shareToken) {
        val token = state.shareToken ?: return@LaunchedEffect
        ExternalIntents.shareText(context, shareMessage.format(token), chooser)
        viewModel.onShareHandled()
    }
    SafetyPanelContent(
        state = state,
        actions =
            SafetyActions(
                onShare = viewModel::onShareTapped,
                onSos = viewModel::onSosTapped,
                onSosConfirm = viewModel::onSosConfirmed,
                onSosDismiss = viewModel::onSosDismissed,
                onTextContacts = {
                    val sent = state.sos as? SosStage.Sent ?: return@SafetyActions
                    val link = "https://maps.google.com/?q=${sent.location.latitude},${sent.location.longitude}"
                    ExternalIntents.composeSms(context, sent.contacts.map { it.phone }, smsText.format(link))
                },
                onCall112 = { ExternalIntents.dialEmergency(context) },
            ),
    )
}

@Composable
fun SafetyPanelContent(
    state: SafetyUiState,
    actions: SafetyActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("safety_panel")) {
        state.shareError?.let { InfoBanner(text = it, kind = BannerKind.ERROR) }
        SecondaryButton(
            text = stringResource(R.string.safety_share_action),
            onClick = actions.onShare,
            enabled = !state.sharing,
            icon = Icons.Filled.Share,
            modifier = Modifier.testTag("share_trip"),
        )
        EmergencyButton(
            text = stringResource(R.string.safety_sos_action),
            onClick = actions.onSos,
            icon = Icons.Filled.Warning,
            modifier = Modifier.testTag("sos_button"),
        )
    }
    SosDialog(state.sos, actions)
}

@Composable
private fun SosDialog(
    stage: SosStage,
    actions: SafetyActions,
) {
    when (stage) {
        SosStage.Idle -> Unit
        SosStage.Confirming ->
            AlertDialog(
                onDismissRequest = actions.onSosDismiss,
                title = { Text(stringResource(R.string.safety_sos_confirm_title)) },
                text = { Text(stringResource(R.string.safety_sos_confirm_body)) },
                confirmButton = {
                    TextButton(onClick = actions.onSosConfirm, modifier = Modifier.testTag("sos_confirm")) {
                        Text(stringResource(R.string.safety_sos_confirm_action), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = { TextButton(onClick = actions.onSosDismiss) { Text(stringResource(R.string.safety_sos_cancel_action)) } },
            )
        SosStage.Sending ->
            AlertDialog(
                onDismissRequest = {},
                confirmButton = {},
                title = { Text(stringResource(R.string.safety_sos_sending)) },
                text = { CircularProgressIndicator() },
            )
        is SosStage.Sent ->
            AlertDialog(
                onDismissRequest = actions.onSosDismiss,
                title = { Text(stringResource(R.string.safety_sos_sent_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.safety_sos_sent_body))
                        if (stage.contacts.isEmpty()) Text(stringResource(R.string.safety_sos_add_contacts_note))
                    }
                },
                confirmButton = {
                    Column {
                        if (stage.contacts.isNotEmpty()) {
                            TextButton(onClick = actions.onTextContacts, modifier = Modifier.testTag("sos_text_contacts")) {
                                Text(stringResource(R.string.safety_sos_text_contacts_action))
                            }
                        }
                        TextButton(onClick = actions.onCall112) {
                            Text(stringResource(R.string.safety_sos_call_112_action), color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                dismissButton = { TextButton(onClick = actions.onSosDismiss) { Text(stringResource(R.string.safety_sos_close_action)) } },
            )
        is SosStage.Failed ->
            AlertDialog(
                onDismissRequest = actions.onSosDismiss,
                title = { Text(stringResource(R.string.safety_sos_failed_title)) },
                text = { Text(stage.message, modifier = Modifier.testTag("sos_failed")) },
                confirmButton = {
                    TextButton(onClick = actions.onCall112) {
                        Text(stringResource(R.string.safety_sos_call_112_action), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = { TextButton(onClick = actions.onSosDismiss) { Text(stringResource(R.string.safety_sos_close_action)) } },
            )
    }
}
