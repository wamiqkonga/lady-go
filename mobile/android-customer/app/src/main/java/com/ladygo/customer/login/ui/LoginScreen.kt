package com.ladygo.customer.login.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ladygo.customer.R
import com.ladygo.customer.login.LoginStep
import com.ladygo.customer.login.LoginUiState
import com.ladygo.customer.login.LoginViewModel
import com.ladygo.customer.ui.components.BannerKind
import com.ladygo.customer.ui.components.BrandHeader
import com.ladygo.customer.ui.components.InfoBanner
import com.ladygo.customer.ui.components.LadyGoTextField
import com.ladygo.customer.ui.components.PrimaryButton
import com.ladygo.customer.ui.components.QuietButton

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
                when (state.step) {
                    LoginStep.PHONE_ENTRY -> PhoneStep(state, onPhoneChanged, onRequestOtp)
                    LoginStep.CODE_ENTRY -> CodeStep(state, onCodeChanged, onVerifyOtp, onBackToPhoneEntry)
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
    onPhoneChanged: (String) -> Unit,
    onRequestOtp: () -> Unit,
) {
    Text(stringResource(R.string.login_phone_title), style = MaterialTheme.typography.headlineMedium)
    Text(
        stringResource(R.string.login_phone_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    LadyGoTextField(
        value = state.phone,
        onValueChange = onPhoneChanged,
        label = "Phone number",
        leadingIcon = Icons.Filled.Phone,
        helper = stringResource(R.string.login_phone_helper),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
    )
    PrimaryButton(
        text = if (state.isLoading) "Sending..." else "Send code",
        onClick = onRequestOtp,
        enabled = state.phone.isNotBlank() && !state.isLoading,
    )
}

@Composable
private fun CodeStep(
    state: LoginUiState,
    onCodeChanged: (String) -> Unit,
    onVerifyOtp: () -> Unit,
    onBackToPhoneEntry: () -> Unit,
) {
    Text(stringResource(R.string.login_code_title), style = MaterialTheme.typography.headlineMedium)
    Text(
        "Enter the code sent to ${state.phone}.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    LadyGoTextField(
        value = state.code,
        onValueChange = onCodeChanged,
        label = "6-digit code",
        leadingIcon = Icons.Filled.Lock,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
    )
    PrimaryButton(
        text = if (state.isLoading) "Verifying..." else "Sign in",
        onClick = onVerifyOtp,
        enabled = state.code.isNotBlank() && !state.isLoading,
    )
    QuietButton(text = "Use a different number", onClick = onBackToPhoneEntry, enabled = !state.isLoading)
}
