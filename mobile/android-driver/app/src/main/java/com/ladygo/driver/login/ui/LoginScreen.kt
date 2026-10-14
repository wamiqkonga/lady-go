package com.ladygo.driver.login.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ladygo.driver.login.LoginStep
import com.ladygo.driver.login.LoginUiState
import com.ladygo.driver.login.LoginViewModel

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
        onPhoneChanged = viewModel::onPhoneChanged,
        onCodeChanged = viewModel::onCodeChanged,
        onRequestOtp = viewModel::onRequestOtp,
        onVerifyOtp = viewModel::onVerifyOtp,
        onBackToPhoneEntry = viewModel::onBackToPhoneEntry,
    )
}

@Composable
fun LoginContent(
    state: LoginUiState,
    onPhoneChanged: (String) -> Unit,
    onCodeChanged: (String) -> Unit,
    onRequestOtp: () -> Unit,
    onVerifyOtp: () -> Unit,
    onBackToPhoneEntry: () -> Unit,
) {
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding).padding(24.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = "Lady Go Driver", style = MaterialTheme.typography.headlineMedium)

            when (state.step) {
                LoginStep.PHONE_ENTRY -> {
                    Text(text = "Enter your phone number to sign in.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = state.phone,
                        onValueChange = onPhoneChanged,
                        label = { Text("Phone number") },
                        keyboardOptions =
                            androidx.compose.foundation.text
                                .KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = onRequestOtp,
                        enabled = state.phone.isNotBlank() && !state.isLoading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.isLoading) "Sending..." else "Send code")
                    }
                }
                LoginStep.CODE_ENTRY -> {
                    Text(text = "Enter the code sent to ${state.phone}.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = state.code,
                        onValueChange = onCodeChanged,
                        label = { Text("6-digit code") },
                        keyboardOptions =
                            androidx.compose.foundation.text
                                .KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = onVerifyOtp,
                        enabled = state.code.isNotBlank() && !state.isLoading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.isLoading) "Verifying..." else "Sign in")
                    }
                    TextButton(onClick = onBackToPhoneEntry, enabled = !state.isLoading) {
                        Text("Use a different number")
                    }
                }
            }

            state.error?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
