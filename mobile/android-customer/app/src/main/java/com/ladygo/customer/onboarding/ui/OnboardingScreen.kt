package com.ladygo.customer.onboarding.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.ladygo.customer.ui.components.BannerKind
import com.ladygo.customer.ui.components.InfoBanner
import com.ladygo.customer.ui.components.LadyGoCard
import com.ladygo.customer.ui.components.LadyGoScreen
import com.ladygo.customer.ui.components.LadyGoTextField
import com.ladygo.customer.ui.components.LoadingState
import com.ladygo.customer.ui.components.PrimaryButton
import com.ladygo.customer.ui.components.QuietButton
import com.ladygo.customer.ui.components.StateIllustration
import com.ladygo.customer.ui.components.StepDots

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
    val content = state.state
    val step = if (content is OnboardingState.NeedsDeclaration) 1 else 0
    val showSteps = !state.isLoading && (content == OnboardingState.NeedsName || content is OnboardingState.NeedsDeclaration)
    LadyGoScreen(
        bottomBar = { QuietButton(text = stringResource(R.string.onboarding_logout_action), onClick = onLogout) },
    ) {
        Spacer(Modifier.height(32.dp))
        if (showSteps) StepDots(current = step, total = 2)
        when {
            state.isLoading || content == null || content == OnboardingState.Ready ->
                LoadingState(
                    message = stringResource(R.string.onboarding_loading),
                )
            content is OnboardingState.LoadError -> LoadErrorSection(content.message, onRetry)
            content == OnboardingState.NeedsName -> NameSection(state, onNameChanged, onSaveName)
            content is OnboardingState.NeedsDeclaration -> DeclarationSection(state, content, onAcceptDeclaration)
        }
    }
}

@Composable
private fun LoadErrorSection(
    message: String,
    onRetry: () -> Unit,
) {
    StateIllustration(
        icon = Icons.Filled.CloudOff,
        title = stringResource(R.string.onboarding_error_title),
        body = null,
        tint = MaterialTheme.colorScheme.error,
        container = MaterialTheme.colorScheme.errorContainer,
    )
    InfoBanner(text = message, kind = BannerKind.ERROR)
    PrimaryButton(text = stringResource(R.string.onboarding_retry_action), onClick = onRetry)
}

@Composable
private fun NameSection(
    state: OnboardingUiState,
    onNameChanged: (String) -> Unit,
    onSaveName: () -> Unit,
) {
    Text(stringResource(R.string.onboarding_name_title), style = MaterialTheme.typography.headlineLarge)
    Text(
        stringResource(R.string.onboarding_name_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    LadyGoTextField(
        value = state.nameInput,
        onValueChange = onNameChanged,
        label = stringResource(R.string.onboarding_name_label),
        leadingIcon = Icons.Filled.Person,
        modifier = Modifier.testTag("onboarding_name_field"),
    )
    ErrorText(state.error)
    PrimaryButton(
        text = stringResource(R.string.onboarding_continue_action),
        onClick = onSaveName,
        enabled = state.nameInput.isNotBlank() && !state.isSubmitting,
        loading = state.isSubmitting,
    )
}

@Composable
private fun DeclarationSection(
    state: OnboardingUiState,
    declaration: OnboardingState.NeedsDeclaration,
    onAccept: () -> Unit,
) {
    Text(stringResource(R.string.onboarding_declaration_title), style = MaterialTheme.typography.headlineLarge)
    Text(
        stringResource(R.string.onboarding_declaration_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    LadyGoCard {
        Text(
            text = declaration.text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag("onboarding_declaration_text"),
        )
    }
    ErrorText(state.error)
    PrimaryButton(
        text = stringResource(R.string.onboarding_declaration_accept_action),
        onClick = onAccept,
        enabled = !state.isSubmitting,
        loading = state.isSubmitting,
    )
}

@Composable
private fun ErrorText(message: String?) {
    message?.let { InfoBanner(text = it, kind = BannerKind.ERROR, modifier = Modifier.testTag("onboarding_error")) }
}
