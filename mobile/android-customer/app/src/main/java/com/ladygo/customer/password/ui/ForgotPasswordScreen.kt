package com.ladygo.customer.password.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ladygo.customer.R
import com.ladygo.customer.login.ui.AuthScaffold
import com.ladygo.customer.login.ui.AuthTitle
import com.ladygo.customer.password.ForgotPasswordUiState
import com.ladygo.customer.password.ForgotPasswordViewModel
import com.ladygo.customer.password.ForgotStep
import com.ladygo.customer.ui.components.BannerKind
import com.ladygo.customer.ui.components.InfoBanner
import com.ladygo.customer.ui.components.LadyGoTextField
import com.ladygo.customer.ui.components.PasswordField
import com.ladygo.customer.ui.components.PrimaryButton
import com.ladygo.customer.ui.components.QuietButton

@Composable
fun ForgotPasswordScreen(
    onBackToLogin: () -> Unit,
    viewModel: ForgotPasswordViewModel = hiltViewModel(),
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    ForgotPasswordContent(
        state = state,
        actions =
            ForgotPasswordActions(
                onEmailChanged = viewModel::onEmailChanged,
                onCodeChanged = viewModel::onCodeChanged,
                onPasswordChanged = viewModel::onPasswordChanged,
                onConfirmPasswordChanged = viewModel::onConfirmPasswordChanged,
                onSendCode = viewModel::onSendCode,
                onResetPassword = viewModel::onResetPassword,
                onBackToEmail = viewModel::onBackToEmail,
                onBackToLogin = onBackToLogin,
            ),
    )
}

/** What the forgot-password screen can do; the real screen wires the view model, tests pass what they need. */
data class ForgotPasswordActions(
    val onEmailChanged: (String) -> Unit = {},
    val onCodeChanged: (String) -> Unit = {},
    val onPasswordChanged: (String) -> Unit = {},
    val onConfirmPasswordChanged: (String) -> Unit = {},
    val onSendCode: () -> Unit = {},
    val onResetPassword: () -> Unit = {},
    val onBackToEmail: () -> Unit = {},
    val onBackToLogin: () -> Unit = {},
)

@Composable
fun ForgotPasswordContent(
    state: ForgotPasswordUiState,
    actions: ForgotPasswordActions,
) {
    AuthScaffold(error = state.error) {
        when (state.step) {
            ForgotStep.EMAIL -> {
                AuthTitle(stringResource(R.string.forgot_title), stringResource(R.string.forgot_body))
                LadyGoTextField(
                    value = state.email,
                    onValueChange = actions.onEmailChanged,
                    label = stringResource(R.string.register_email_label),
                    leadingIcon = Icons.Filled.Email,
                    isError = state.emailError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                    modifier = Modifier.testTag("forgot_email"),
                )
                PrimaryButton(
                    text = if (state.isLoading) stringResource(R.string.sending) else stringResource(R.string.register_send_code),
                    onClick = actions.onSendCode,
                    enabled = state.email.isNotBlank() && !state.emailError && !state.isLoading,
                )
                QuietButton(
                    text = stringResource(R.string.forgot_back_to_login),
                    onClick = actions.onBackToLogin,
                    enabled = !state.isLoading,
                )
            }
            ForgotStep.NEW_PASSWORD -> {
                AuthTitle(stringResource(R.string.forgot_code_title), stringResource(R.string.forgot_code_body, state.email))
                LadyGoTextField(
                    value = state.code,
                    onValueChange = actions.onCodeChanged,
                    label = stringResource(R.string.code_label),
                    leadingIcon = Icons.Filled.Lock,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Next),
                    modifier = Modifier.testTag("forgot_code"),
                )
                PasswordField(
                    value = state.password,
                    onValueChange = actions.onPasswordChanged,
                    label = stringResource(R.string.password_new_label),
                    helper = stringResource(R.string.password_rules),
                    isError = state.passwordError,
                    modifier = Modifier.testTag("forgot_password"),
                )
                PasswordField(
                    value = state.confirmPassword,
                    onValueChange = actions.onConfirmPasswordChanged,
                    label = stringResource(R.string.password_confirm_label),
                    isError = state.confirmError,
                    imeAction = ImeAction.Done,
                    modifier = Modifier.testTag("forgot_confirm"),
                )
                PrimaryButton(
                    text = if (state.isLoading) stringResource(R.string.saving) else stringResource(R.string.forgot_reset_action),
                    onClick = actions.onResetPassword,
                    enabled = state.canReset,
                )
                QuietButton(
                    text = stringResource(R.string.forgot_change_email),
                    onClick = actions.onBackToEmail,
                    enabled = !state.isLoading,
                )
            }
            ForgotStep.DONE -> {
                InfoBanner(
                    text = stringResource(R.string.forgot_done),
                    kind = BannerKind.SUCCESS,
                    modifier = Modifier.testTag("forgot_done"),
                )
                PrimaryButton(text = stringResource(R.string.forgot_back_to_login), onClick = actions.onBackToLogin)
            }
        }
    }
}
