package com.ladygo.customer.login.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
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
import com.ladygo.customer.login.LoginUiState
import com.ladygo.customer.login.LoginViewModel
import com.ladygo.customer.ui.components.LadyGoTextField
import com.ladygo.customer.ui.components.PasswordField
import com.ladygo.customer.ui.components.PrimaryButton
import com.ladygo.customer.ui.components.QuietButton

@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    onRegister: () -> Unit,
    onForgotPassword: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    LaunchedEffect(state.loggedIn) { if (state.loggedIn) onLoggedIn() }
    LoginContent(
        state = state,
        onIdentifierChanged = viewModel::onIdentifierChanged,
        onPasswordChanged = viewModel::onPasswordChanged,
        onLogin = viewModel::onLogin,
        onRegister = onRegister,
        onForgotPassword = onForgotPassword,
    )
}

@Composable
fun LoginContent(
    state: LoginUiState,
    onIdentifierChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onLogin: () -> Unit,
    onRegister: () -> Unit,
    onForgotPassword: () -> Unit,
) {
    AuthScaffold(error = state.error) {
        AuthTitle(stringResource(R.string.login_title), stringResource(R.string.login_body))
        LadyGoTextField(
            value = state.identifier,
            onValueChange = onIdentifierChanged,
            label = stringResource(R.string.login_identifier_label),
            leadingIcon = Icons.Filled.Person,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.testTag("login_identifier"),
        )
        PasswordField(
            value = state.password,
            onValueChange = onPasswordChanged,
            label = stringResource(R.string.password_label),
            imeAction = ImeAction.Done,
            modifier = Modifier.testTag("login_password"),
        )
        PrimaryButton(
            text = if (state.isLoading) stringResource(R.string.login_signing_in) else stringResource(R.string.login_action),
            onClick = onLogin,
            enabled = state.canSubmit,
        )
        QuietButton(text = stringResource(R.string.login_forgot_action), onClick = onForgotPassword, enabled = !state.isLoading)
        QuietButton(text = stringResource(R.string.login_create_action), onClick = onRegister, enabled = !state.isLoading)
    }
}
