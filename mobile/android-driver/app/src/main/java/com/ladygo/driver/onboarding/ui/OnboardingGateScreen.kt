package com.ladygo.driver.onboarding.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ladygo.driver.api.generated.models.DriverProfileResponse.VerificationStatus
import com.ladygo.driver.onboarding.DocumentNote
import com.ladygo.driver.onboarding.GateUiState
import com.ladygo.driver.onboarding.OnboardingGateViewModel
import com.ladygo.driver.onboarding.OnboardingStatus
import com.ladygo.driver.onboarding.isApproved
import com.ladygo.driver.ui.components.BannerKind
import com.ladygo.driver.ui.components.InfoBanner
import com.ladygo.driver.ui.components.LadyGoScreen
import com.ladygo.driver.ui.components.LoadingState
import com.ladygo.driver.ui.components.PrimaryButton
import com.ladygo.driver.ui.components.QuietButton
import com.ladygo.driver.ui.components.SecondaryButton
import com.ladygo.driver.ui.components.SectionLabel
import com.ladygo.driver.ui.components.StateIllustration
import com.ladygo.driver.ui.theme.LadyGoTheme

@Composable
fun OnboardingGateScreen(
    onApproved: () -> Unit,
    viewModel: OnboardingGateViewModel = hiltViewModel(),
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value

    LaunchedEffect(state.isApproved()) {
        if (state.isApproved()) onApproved()
    }

    // Approval happens on the admin side while the driver is elsewhere (often the website), so
    // re-check whenever the app comes back to the foreground. The first ON_RESUME is the initial
    // launch, which the ViewModel's own init-time load already covers.
    var seenFirstResume by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (seenFirstResume) viewModel.refresh() else seenFirstResume = true
    }

    OnboardingGateContent(state = state, onRetry = viewModel::refresh, onLogout = viewModel::onLogout)
}

@Composable
private fun OnboardingGateContent(
    state: GateUiState,
    onRetry: () -> Unit,
    onLogout: () -> Unit,
) {
    val context = LocalContext.current
    LadyGoScreen {
        Spacer(Modifier.height(24.dp))
        if (state.isLoading) {
            LoadingState(message = "Checking your application...")
            return@LadyGoScreen
        }
        when (val status = state.status) {
            null -> Unit
            is OnboardingStatus.LoadError -> {
                StateIllustration(
                    icon = Icons.Filled.CloudOff,
                    title = "Couldn't load your status",
                    body = null,
                    tint = MaterialTheme.colorScheme.error,
                    container = MaterialTheme.colorScheme.errorContainer,
                )
                InfoBanner(text = status.message, kind = BannerKind.ERROR)
                PrimaryButton(text = "Retry", onClick = onRetry)
            }
            is OnboardingStatus.Known -> StatusBody(status.verificationStatus, status.documentNotes, context, onRetry)
            OnboardingStatus.NotStarted -> StatusBody(null, emptyList(), context, onRetry)
        }
        QuietButton(text = "Log out", onClick = onLogout)
    }
}

@Composable
private fun StatusBody(
    status: VerificationStatus?,
    documentNotes: List<DocumentNote>,
    context: android.content.Context,
    onRetry: () -> Unit,
) {
    val (headline, body) = headlineAndBody(status)
    val look = statusLook(status)
    StateIllustration(icon = look.icon, title = headline, body = body, tint = look.tint(), container = look.container())

    if (status == VerificationStatus.REJECTED && documentNotes.isNotEmpty()) {
        SectionLabel("What needs fixing")
        documentNotes.forEach { note ->
            InfoBanner(text = "${note.type}: ${note.note ?: "No reason given."}", kind = BannerKind.WARNING)
        }
    }

    if (status != VerificationStatus.PENDING_REVIEW) {
        PrimaryButton(
            text = "Continue on the Lady Go website",
            onClick = { openDriverWebsite(context) },
        )
    }
    SecondaryButton(text = "Check again", onClick = onRetry)
}

private class StatusLook(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val tint: @Composable () -> androidx.compose.ui.graphics.Color,
    val container: @Composable () -> androidx.compose.ui.graphics.Color,
)

private fun statusLook(status: VerificationStatus?): StatusLook =
    when (status) {
        VerificationStatus.PENDING_REVIEW ->
            StatusLook(Icons.Filled.HourglassTop, { LadyGoTheme.extra.warning }, { LadyGoTheme.extra.warningContainer })
        VerificationStatus.REJECTED ->
            StatusLook(Icons.Filled.EditNote, { MaterialTheme.colorScheme.secondary }, { MaterialTheme.colorScheme.secondaryContainer })
        VerificationStatus.SUSPENDED ->
            StatusLook(Icons.Filled.Block, { MaterialTheme.colorScheme.error }, { MaterialTheme.colorScheme.errorContainer })
        VerificationStatus.APPROVED ->
            StatusLook(Icons.Filled.VerifiedUser, { LadyGoTheme.extra.success }, { LadyGoTheme.extra.successContainer })
        else -> StatusLook(Icons.Filled.Badge, { MaterialTheme.colorScheme.primary }, { MaterialTheme.colorScheme.primaryContainer })
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
