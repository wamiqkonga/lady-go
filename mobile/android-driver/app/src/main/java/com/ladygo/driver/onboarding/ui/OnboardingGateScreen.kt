package com.ladygo.driver.onboarding.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ladygo.driver.BuildConfig
import com.ladygo.driver.api.generated.models.DriverProfileResponse.VerificationStatus
import com.ladygo.driver.onboarding.DocumentNote
import com.ladygo.driver.onboarding.GateUiState
import com.ladygo.driver.onboarding.OnboardingGateViewModel
import com.ladygo.driver.onboarding.OnboardingStatus
import com.ladygo.driver.onboarding.isApproved

@Composable
fun OnboardingGateScreen(
    onApproved: () -> Unit,
    viewModel: OnboardingGateViewModel = hiltViewModel(),
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value

    LaunchedEffect(state.isApproved()) {
        if (state.isApproved()) onApproved()
    }

    OnboardingGateContent(state = state, onRetry = viewModel::refresh)
}

@Composable
private fun OnboardingGateContent(
    state: GateUiState,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.isLoading) {
                CircularProgressIndicator()
                return@Column
            }
            when (val status = state.status) {
                null -> Unit
                is OnboardingStatus.LoadError -> {
                    Text("Couldn't load your status", style = MaterialTheme.typography.headlineSmall)
                    Text(status.message, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onRetry) { Text("Retry") }
                }
                is OnboardingStatus.Known -> StatusBody(status.verificationStatus, status.documentNotes, context)
                OnboardingStatus.NotStarted -> StatusBody(null, emptyList(), context)
            }
        }
    }
}

@Composable
private fun StatusBody(
    status: VerificationStatus?,
    documentNotes: List<DocumentNote>,
    context: android.content.Context,
) {
    val (headline, body) = headlineAndBody(status)
    Text(headline, style = MaterialTheme.typography.headlineSmall)
    Text(body, style = MaterialTheme.typography.bodyMedium)

    if (status == VerificationStatus.REJECTED && documentNotes.isNotEmpty()) {
        Text("What needs fixing:", style = MaterialTheme.typography.titleMedium)
        documentNotes.forEach { note ->
            Text("- ${note.type}: ${note.note ?: "No reason given."}", style = MaterialTheme.typography.bodySmall)
        }
    }

    Button(onClick = {
        context.startActivity(Intent(Intent.ACTION_VIEW, BuildConfig.DRIVER_REGISTRATION_URL.toUri()))
    }) {
        Text("Continue on the Lady Go website")
    }
}

private fun headlineAndBody(status: VerificationStatus?): Pair<String, String> =
    when (status) {
        null ->
            "Let's get you set up" to
                "You haven't started your driver application yet. Finish it on the Lady Go website to start driving."
        VerificationStatus.NOT_SUBMITTED ->
            "Let's get you set up" to
                "Finish your driver application on the Lady Go website to start driving."
        VerificationStatus.PENDING_REVIEW ->
            "Application under review" to
                "We're reviewing your documents. This usually takes a day or two - we'll let you know when it's done."
        VerificationStatus.REJECTED ->
            "Changes needed" to
                "Some of your documents need attention. Fix them on the Lady Go website and we'll review again."
        VerificationStatus.SUSPENDED ->
            "Account suspended" to
                "Your driver account is currently suspended. Contact support or check the website for details."
        VerificationStatus.APPROVED -> "Approved" to "You're all set."
    }
