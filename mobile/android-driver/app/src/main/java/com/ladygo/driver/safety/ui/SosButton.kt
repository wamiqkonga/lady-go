package com.ladygo.driver.safety.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ladygo.driver.R
import com.ladygo.driver.ride.ExternalIntents
import com.ladygo.driver.safety.SosStage
import com.ladygo.driver.safety.SosViewModel
import com.ladygo.driver.ui.components.EmergencyButton
import java.util.UUID

/** The in-ride SOS button for the real screen: wires [SosButtonContent] to [SosViewModel] and the dialer. */
@Composable
fun SosButton(
    bookingId: UUID?,
    viewModel: SosViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val dialerFailed = stringResource(R.string.ride_dialer_failed)
    SosButtonContent(
        stage = viewModel.stage.collectAsStateWithLifecycle().value,
        onTap = viewModel::onTapped,
        onConfirm = { viewModel.onConfirmed(bookingId) },
        onDismiss = viewModel::onDismissed,
        onCall112 = { if (!ExternalIntents.dialEmergency(context)) Toast.makeText(context, dialerFailed, Toast.LENGTH_LONG).show() },
    )
}

@Composable
fun SosButtonContent(
    stage: SosStage,
    onTap: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onCall112: () -> Unit,
) {
    EmergencyButton(
        text = stringResource(R.string.sos_action),
        onClick = onTap,
        icon = Icons.Filled.Warning,
        modifier = Modifier.testTag("sos_button"),
    )
    when (stage) {
        SosStage.Idle -> Unit
        SosStage.Confirming ->
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.sos_confirm_title)) },
                text = { Text(stringResource(R.string.sos_confirm_body)) },
                confirmButton = {
                    TextButton(onClick = onConfirm, modifier = Modifier.testTag("sos_confirm")) {
                        Text(stringResource(R.string.sos_confirm_action), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.sos_cancel_action)) } },
            )
        SosStage.Sending ->
            AlertDialog(
                onDismissRequest = {},
                confirmButton = {},
                title = { Text(stringResource(R.string.sos_sending)) },
                text = { CircularProgressIndicator() },
            )
        SosStage.Sent ->
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.sos_sent_title)) },
                text = { Text(stringResource(R.string.sos_sent_body), modifier = Modifier.testTag("sos_sent")) },
                confirmButton = {
                    TextButton(onClick = onCall112) {
                        Text(stringResource(R.string.sos_call_112_action), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.sos_close_action)) } },
            )
        is SosStage.Failed ->
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.sos_failed_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stage.message, modifier = Modifier.testTag("sos_failed"))
                    }
                },
                confirmButton = {
                    TextButton(onClick = onCall112) {
                        Text(stringResource(R.string.sos_call_112_action), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.sos_close_action)) } },
            )
    }
}
