package com.ladygo.customer.onboarding.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ladygo.customer.R
import com.ladygo.customer.onboarding.OnboardingState
import com.ladygo.customer.onboarding.OnboardingUiState
import com.ladygo.customer.onboarding.OnboardingViewModel

@Composable
fun OnboardingScreen(
    onReady: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    LaunchedEffect(state.isReady) {
        if (state.isReady) onReady()
    }
    OnboardingContent(
        state = state,
        onNameChanged = viewModel::onNameChanged,
        onSaveName = viewModel::onSaveName,
        onAcceptDeclaration = viewModel::onAcceptDeclaration,
        onRetry = { viewModel.refresh() },
        onLogout = viewModel::onLogout,
    )
}

@Composable
fun OnboardingContent(
    state: OnboardingUiState,
    onNameChanged: (String) -> Unit,
    onSaveName: () -> Unit,
    onAcceptDeclaration: () -> Unit,
    onRetry: () -> Unit,
    onLogout: () -> Unit,
) {
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val content = state.state
            when {
                state.isLoading || content == null || content == OnboardingState.Ready -> CircularProgressIndicator()
                content is OnboardingState.LoadError -> LoadErrorSection(content.message, onRetry)
                content == OnboardingState.NeedsName -> NameSection(state, onNameChanged, onSaveName)
                content is OnboardingState.NeedsDeclaration -> DeclarationSection(state, content, onAcceptDeclaration)
            }
            // Always a way out: wrong number, or a load error that never clears, must not trap the rider.
            TextButton(onClick = onLogout) { Text(stringResource(R.string.onboarding_logout_action)) }
        }
    }
}

@Composable
private fun LoadErrorSection(
    message: String,
    onRetry: () -> Unit,
) {
    Text(text = message, color = MaterialTheme.colorScheme.error)
    Button(onClick = onRetry) { Text(stringResource(R.string.onboarding_retry_action)) }
}

@Composable
private fun NameSection(
    state: OnboardingUiState,
    onNameChanged: (String) -> Unit,
    onSaveName: () -> Unit,
) {
    Text(stringResource(R.string.onboarding_name_title), style = MaterialTheme.typography.headlineMedium)
    Text(stringResource(R.string.onboarding_name_hint))
    OutlinedTextField(
        value = state.nameInput,
        onValueChange = onNameChanged,
        label = { Text(stringResource(R.string.onboarding_name_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("onboarding_name_field"),
    )
    ErrorText(state.error)
    Button(
        onClick = onSaveName,
        enabled = state.nameInput.isNotBlank() && !state.isSubmitting,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.onboarding_continue_action)) }
}

@Composable
private fun DeclarationSection(
    state: OnboardingUiState,
    declaration: OnboardingState.NeedsDeclaration,
    onAccept: () -> Unit,
) {
    Text(stringResource(R.string.onboarding_declaration_title), style = MaterialTheme.typography.headlineMedium)
    Text(text = declaration.text, modifier = Modifier.testTag("onboarding_declaration_text"))
    ErrorText(state.error)
    Button(onClick = onAccept, enabled = !state.isSubmitting, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.onboarding_declaration_accept_action))
    }
}

@Composable
private fun ErrorText(message: String?) {
    message?.let { Text(text = it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("onboarding_error")) }
}
