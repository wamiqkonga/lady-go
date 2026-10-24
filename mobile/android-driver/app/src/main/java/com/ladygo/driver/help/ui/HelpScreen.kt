package com.ladygo.driver.help.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ladygo.driver.R
import com.ladygo.driver.help.HelpUiState
import com.ladygo.driver.help.HelpViewModel
import com.ladygo.driver.ride.ExternalIntents
import com.ladygo.driver.ui.components.BannerKind
import com.ladygo.driver.ui.components.EmergencyButton
import com.ladygo.driver.ui.components.InfoBanner
import com.ladygo.driver.ui.components.LadyGoCard
import com.ladygo.driver.ui.components.LadyGoScreen
import com.ladygo.driver.ui.components.LoadingState
import com.ladygo.driver.ui.components.PrimaryButton
import com.ladygo.driver.ui.components.QuietButton
import com.ladygo.driver.ui.components.SecondaryButton

@Composable
fun HelpScreen(
    onBack: () -> Unit,
    viewModel: HelpViewModel = hiltViewModel(),
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    val dialerFailed = stringResource(R.string.ride_dialer_failed)
    val copyFailed = stringResource(R.string.help_copy_failed)
    val whatsAppFailed = stringResource(R.string.help_whatsapp_failed)
    HelpContent(
        state = state,
        copied = copied,
        onBack = onBack,
        onRetry = viewModel::onRetry,
        onCallSupport = { number ->
            if (!ExternalIntents.dial(context, number)) Toast.makeText(context, dialerFailed, Toast.LENGTH_LONG).show()
        },
        onWhatsApp = { digits ->
            if (!ExternalIntents.openWhatsApp(context, digits)) Toast.makeText(context, whatsAppFailed, Toast.LENGTH_LONG).show()
        },
        onCall112 = {
            if (!ExternalIntents.dialEmergency(context)) Toast.makeText(context, dialerFailed, Toast.LENGTH_LONG).show()
        },
        onCopyDiagnostics = {
            copied = copyToClipboard(context, viewModel.diagnostics())
            if (!copied) Toast.makeText(context, copyFailed, Toast.LENGTH_LONG).show()
        },
    )
}

@Suppress("LongParameterList")
@Composable
fun HelpContent(
    state: HelpUiState,
    copied: Boolean,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onCallSupport: (String) -> Unit,
    onWhatsApp: (String) -> Unit,
    onCall112: () -> Unit,
    onCopyDiagnostics: () -> Unit,
) {
    LadyGoScreen(
        title = stringResource(R.string.help_title),
        onBack = onBack,
        backDescription = stringResource(R.string.common_back),
    ) {
        LadyGoCard {
            Text(stringResource(R.string.help_support_title), style = MaterialTheme.typography.titleMedium)
            SupportBody(state, onRetry, onCallSupport, onWhatsApp)
        }
        EmergencyButton(
            text = stringResource(R.string.ride_call_112_action),
            onClick = onCall112,
            icon = Icons.Filled.Phone,
            modifier = Modifier.testTag("help_call_112"),
        )
        LadyGoCard {
            Text(stringResource(R.string.help_diagnostics_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.help_diagnostics_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SecondaryButton(
                text = stringResource(R.string.help_copy_diagnostics),
                onClick = onCopyDiagnostics,
                // Wait for the driver reference (or its "unavailable" fallback) so the copied text is complete.
                enabled = state.driverReference != null,
                icon = Icons.Filled.ContentCopy,
            )
            if (copied) InfoBanner(text = stringResource(R.string.help_diagnostics_copied), kind = BannerKind.SUCCESS)
        }
    }
}

@Composable
private fun SupportBody(
    state: HelpUiState,
    onRetry: () -> Unit,
    onCallSupport: (String) -> Unit,
    onWhatsApp: (String) -> Unit,
) {
    val contact = state.contact
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            state.isLoading -> LoadingState(message = stringResource(R.string.help_loading))
            contact == null -> {
                InfoBanner(text = state.error ?: stringResource(R.string.help_load_failed), kind = BannerKind.ERROR)
                QuietButton(text = stringResource(R.string.common_retry), onClick = onRetry)
            }
            contact.isEmpty -> Text(stringResource(R.string.help_coming_soon), style = MaterialTheme.typography.bodyLarge)
            else -> {
                contact.phone?.let { phone ->
                    PrimaryButton(
                        text = stringResource(R.string.help_call_support),
                        onClick = { onCallSupport(phone) },
                        icon = Icons.Filled.Phone,
                        modifier = Modifier.testTag("help_call_support"),
                    )
                }
                contact.whatsappDigits?.let { digits ->
                    SecondaryButton(
                        text = stringResource(R.string.help_whatsapp),
                        onClick = { onWhatsApp(digits) },
                        icon = Icons.AutoMirrored.Filled.Chat,
                        modifier = Modifier.testTag("help_whatsapp"),
                    )
                }
            }
        }
    }
}

/** True only when the text really went to the clipboard. */
internal fun copyToClipboard(
    context: Context,
    text: String,
): Boolean {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
    clipboard.setPrimaryClip(ClipData.newPlainText("Lady Go diagnostics", text))
    return true
}
