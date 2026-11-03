package com.ladygo.driver.login.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ladygo.driver.R
import com.ladygo.driver.auth.BlockedReason
import com.ladygo.driver.login.LoginStep
import com.ladygo.driver.login.LoginUiState
import com.ladygo.driver.login.LoginViewModel
import com.ladygo.driver.ui.components.BannerKind
import com.ladygo.driver.ui.components.BrandHeader
import com.ladygo.driver.ui.components.InfoBanner
import com.ladygo.driver.ui.components.LadyGoTextField
import com.ladygo.driver.ui.components.PrimaryButton
import com.ladygo.driver.ui.components.QuietButton

@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value

    LaunchedEffect(state.loggedIn) {
        if (state.loggedIn) onLoggedIn()
    }

    LoginContent(
        state = state,
        actions =
            LoginActions(
                onPhoneChanged = viewModel::onPhoneChanged,
                onEmailChanged = viewModel::onEmailChanged,
                onCodeChanged = viewModel::onCodeChanged,
                onRequestOtp = viewModel::onRequestOtp,
                onVerifyOtp = viewModel::onVerifyOtp,
                onBackToPhoneEntry = viewModel::onBackToPhoneEntry,
                onDismissBlocked = viewModel::onDismissBlocked,
            ),
    )
}

/** What the sign-in screen can do; the real screen wires the view model, tests pass what they need. */
data class LoginActions(
    val onPhoneChanged: (String) -> Unit = {},
    val onEmailChanged: (String) -> Unit = {},
    val onCodeChanged: (String) -> Unit = {},
    val onRequestOtp: () -> Unit = {},
    val onVerifyOtp: () -> Unit = {},
    val onBackToPhoneEntry: () -> Unit = {},
    val onDismissBlocked: () -> Unit = {},
)

@Composable
fun LoginContent(
    state: LoginUiState,
    actions: LoginActions,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .imePadding(),
    ) {
        BrandHeader(title = stringResource(R.string.login_hero_title), subtitle = stringResource(R.string.login_hero_subtitle))
        Surface(
            color = MaterialTheme.colorScheme.background,
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
            modifier = Modifier.fillMaxWidth().offset(y = (-28).dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val blocked = state.blocked
                when {
                    blocked != null -> BlockedStep(blocked, actions.onDismissBlocked)
                    state.step == LoginStep.PHONE_ENTRY -> PhoneStep(state, actions)
                    else -> CodeStep(state, actions)
                }
                state.error?.let { InfoBanner(text = it, kind = BannerKind.ERROR) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        stringResource(R.string.login_privacy_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PhoneStep(
    state: LoginUiState,
    actions: LoginActions,
) {
    Text(stringResource(R.string.login_phone_title), style = MaterialTheme.typography.headlineMedium)
    Text(
        stringResource(R.string.login_phone_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    LadyGoTextField(
        value = state.phone,
        onValueChange = actions.onPhoneChanged,
        label = "Phone number",
        leadingIcon = Icons.Filled.Phone,
        helper = stringResource(R.string.login_phone_helper),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
        modifier = Modifier.testTag("login_phone"),
    )
    LadyGoTextField(
        value = state.email,
        onValueChange = actions.onEmailChanged,
        label = "Email",
        leadingIcon = Icons.Filled.Email,
        helper = stringResource(R.string.login_email_helper),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
        modifier = Modifier.testTag("login_email"),
    )
    PrimaryButton(
        text = if (state.isLoading) "Sending..." else "Send code",
        onClick = actions.onRequestOtp,
        enabled = state.canRequestCode,
    )
}

@Composable
private fun CodeStep(
    state: LoginUiState,
    actions: LoginActions,
) {
    Text(stringResource(R.string.login_code_title), style = MaterialTheme.typography.headlineMedium)
    Text(
        stringResource(R.string.login_code_body, state.email),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    LadyGoTextField(
        value = state.code,
        onValueChange = actions.onCodeChanged,
        label = "6-digit code",
        leadingIcon = Icons.Filled.Lock,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
    )
    PrimaryButton(
        text = if (state.isLoading) "Verifying..." else "Sign in",
        onClick = actions.onVerifyOtp,
        enabled = state.code.isNotBlank() && !state.isLoading,
    )
    QuietButton(text = "Use a different number", onClick = actions.onBackToPhoneEntry, enabled = !state.isLoading)
}

/** The code was right, but this driver cannot use the app yet: say why, and how to reach the team. */
@Composable
private fun ColumnScope.BlockedStep(
    reason: BlockedReason,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val supportEmail = stringResource(R.string.support_email)
    val (title, body) =
        when (reason) {
            BlockedReason.UNDER_REVIEW -> R.string.blocked_review_title to R.string.blocked_review_body
            BlockedReason.NOT_REGISTERED -> R.string.blocked_register_title to R.string.blocked_register_body
            BlockedReason.REJECTED -> R.string.blocked_rejected_title to R.string.blocked_rejected_body
            BlockedReason.SUSPENDED -> R.string.blocked_suspended_title to R.string.blocked_suspended_body
        }
    Icon(
        Icons.Filled.Info,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(40.dp),
    )
    Text(stringResource(title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("blocked_title"))
    Text(stringResource(body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(stringResource(R.string.blocked_contact_label), style = MaterialTheme.typography.labelLarge)
    Text(supportEmail, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("blocked_support_email"))
    PrimaryButton(
        text = stringResource(R.string.blocked_email_action),
        onClick = {
            val intent =
                Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$supportEmail"))
                    .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.blocked_email_subject))
            runCatching { context.startActivity(intent) }
        },
        icon = Icons.Filled.Email,
    )
    QuietButton(text = stringResource(R.string.blocked_back_action), onClick = onDismiss)
}
