package com.ladygo.customer.register.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.ladygo.customer.register.RegisterStep
import com.ladygo.customer.register.RegisterUiState
import com.ladygo.customer.register.RegisterViewModel
import com.ladygo.customer.ui.components.LadyGoTextField
import com.ladygo.customer.ui.components.PasswordField
import com.ladygo.customer.ui.components.PrimaryButton
import com.ladygo.customer.ui.components.QuietButton

@Composable
fun RegisterScreen(
    onRegistered: () -> Unit,
    onBackToLogin: () -> Unit,
    viewModel: RegisterViewModel = hiltViewModel(),
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    LaunchedEffect(state.registered) { if (state.registered) onRegistered() }
    RegisterContent(
        state = state,
        actions =
            RegisterActions(
                onPhoneChanged = viewModel::onPhoneChanged,
                onEmailChanged = viewModel::onEmailChanged,
                onCodeChanged = viewModel::onCodeChanged,
                onPasswordChanged = viewModel::onPasswordChanged,
                onConfirmPasswordChanged = viewModel::onConfirmPasswordChanged,
                onSendCode = viewModel::onSendCode,
                onCreateAccount = viewModel::onCreateAccount,
                onBackToDetails = viewModel::onBackToDetails,
                onBackToLogin = onBackToLogin,
            ),
    )
}

/** What the sign-up screen can do; the real screen wires the view model, tests pass what they need. */
data class RegisterActions(
    val onPhoneChanged: (String) -> Unit = {},
    val onEmailChanged: (String) -> Unit = {},
    val onCodeChanged: (String) -> Unit = {},
    val onPasswordChanged: (String) -> Unit = {},
    val onConfirmPasswordChanged: (String) -> Unit = {},
    val onSendCode: () -> Unit = {},
    val onCreateAccount: () -> Unit = {},
    val onBackToDetails: () -> Unit = {},
    val onBackToLogin: () -> Unit = {},
)

@Composable
fun RegisterContent(
    state: RegisterUiState,
    actions: RegisterActions,
) {
    AuthScaffold(error = state.error) {
        when (state.step) {
            RegisterStep.DETAILS -> DetailsStep(state, actions)
            RegisterStep.CODE_AND_PASSWORD -> CodeAndPasswordStep(state, actions)
        }
    }
}

@Composable
private fun ColumnScope.DetailsStep(
    state: RegisterUiState,
    actions: RegisterActions,
) {
    AuthTitle(stringResource(R.string.register_title), stringResource(R.string.register_body))
    LadyGoTextField(
        value = state.phone,
        onValueChange = actions.onPhoneChanged,
        label = stringResource(R.string.register_phone_label),
        leadingIcon = Icons.Filled.Phone,
        helper = stringResource(R.string.login_phone_helper),
        isError = state.phoneError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
        modifier = Modifier.testTag("register_phone"),
    )
    LadyGoTextField(
        value = state.email,
        onValueChange = actions.onEmailChanged,
        label = stringResource(R.string.register_email_label),
        leadingIcon = Icons.Filled.Email,
        helper = stringResource(R.string.register_email_helper),
        isError = state.emailError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
        modifier = Modifier.testTag("register_email"),
    )
    PrimaryButton(
        text = if (state.isLoading) stringResource(R.string.sending) else stringResource(R.string.register_send_code),
        onClick = actions.onSendCode,
        enabled = state.detailsValid && !state.isLoading,
    )
    QuietButton(
        text = stringResource(R.string.register_have_account),
        onClick = actions.onBackToLogin,
        enabled = !state.isLoading,
    )
}

@Composable
private fun ColumnScope.CodeAndPasswordStep(
    state: RegisterUiState,
    actions: RegisterActions,
) {
    AuthTitle(stringResource(R.string.register_code_title), stringResource(R.string.register_code_body, state.email))
    LadyGoTextField(
        value = state.code,
        onValueChange = actions.onCodeChanged,
        label = stringResource(R.string.code_label),
        leadingIcon = Icons.Filled.Lock,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Next),
        modifier = Modifier.testTag("register_code"),
    )
    PasswordField(
        value = state.password,
        onValueChange = actions.onPasswordChanged,
        label = stringResource(R.string.password_new_label),
        helper = stringResource(R.string.password_rules),
        isError = state.passwordError,
        modifier = Modifier.testTag("register_password"),
    )
    PasswordField(
        value = state.confirmPassword,
        onValueChange = actions.onConfirmPasswordChanged,
        label = stringResource(R.string.password_confirm_label),
        isError = state.confirmError,
        imeAction = ImeAction.Done,
        modifier = Modifier.testTag("register_confirm"),
    )
    PrimaryButton(
        text =
            if (state.isLoading) {
                stringResource(
                    R.string.register_creating,
                )
            } else {
                stringResource(R.string.register_create_action)
            },
        onClick = actions.onCreateAccount,
        enabled = state.canCreate,
    )
    QuietButton(
        text = stringResource(R.string.register_change_details),
        onClick = actions.onBackToDetails,
        enabled = !state.isLoading,
    )
}
