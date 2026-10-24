package com.ladygo.driver.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ladygo.driver.R
import com.ladygo.driver.api.generated.models.DriverDocumentResponse
import com.ladygo.driver.onboarding.ui.openDriverWebsite
import com.ladygo.driver.ui.components.BannerKind
import com.ladygo.driver.ui.components.InfoBanner
import com.ladygo.driver.ui.components.LabelValueRow
import com.ladygo.driver.ui.components.LadyGoCard
import com.ladygo.driver.ui.components.LadyGoTabScreen
import com.ladygo.driver.ui.components.LoadingState
import com.ladygo.driver.ui.components.PrimaryButton
import com.ladygo.driver.ui.components.QuietButton
import com.ladygo.driver.ui.components.SecondaryButton
import com.ladygo.driver.ui.components.StateIllustration
import com.ladygo.driver.ui.components.StatusChip
import com.ladygo.driver.ui.components.isLargeFontScale

@Composable
fun MeScreen(
    onOpenHelp: () -> Unit,
    viewModel: MeViewModel = hiltViewModel(),
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose {}
    }
    MeContent(
        state = state,
        onRefresh = viewModel::onRefresh,
        onRetry = viewModel::onRetry,
        onOpenWebsite = { openDriverWebsite(context) },
        onOpenHelp = onOpenHelp,
        onLogout = viewModel::onLogout,
    )
}

@Suppress("LongParameterList")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeContent(
    state: MeUiState,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onOpenWebsite: () -> Unit,
    onOpenHelp: () -> Unit,
    onLogout: () -> Unit,
) {
    LadyGoTabScreen(title = stringResource(R.string.me_title)) {
        PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = onRefresh, modifier = Modifier.weight(1f)) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val overview = state.overview
                when {
                    state.isLoading -> LoadingState(message = stringResource(R.string.me_loading))
                    overview == null -> {
                        StateIllustration(
                            icon = Icons.Filled.ErrorOutline,
                            title = stringResource(R.string.me_error_title),
                            body = state.error,
                        )
                        PrimaryButton(text = stringResource(R.string.common_retry), onClick = onRetry)
                    }
                    else -> {
                        if (state.refreshError != null) {
                            InfoBanner(text = stringResource(R.string.me_refresh_failed), kind = BannerKind.WARNING)
                        }
                        RatingCard(overview.rating, onRetry = onRefresh)
                        DocumentsCard(overview, onOpenWebsite, onRetry = onRefresh)
                    }
                }
                SecondaryButton(
                    text = stringResource(R.string.me_help),
                    onClick = onOpenHelp,
                    icon = Icons.AutoMirrored.Filled.HelpOutline,
                    modifier = Modifier.testTag("me_help"),
                )
                SecondaryButton(text = stringResource(R.string.me_log_out), onClick = onLogout)
            }
        }
    }
}

@Composable
private fun SectionError(
    message: String,
    onRetry: () -> Unit,
) {
    InfoBanner(text = message, kind = BannerKind.ERROR)
    QuietButton(text = stringResource(R.string.common_retry), onClick = onRetry)
}

@Composable
private fun RatingCard(
    part: Part<RatingSummary>,
    onRetry: () -> Unit,
) {
    LadyGoCard {
        Text(stringResource(R.string.me_rating_title), style = MaterialTheme.typography.titleMedium)
        when (part) {
            is Part.Failed -> SectionError(part.message, onRetry)
            is Part.Loaded -> RatingBody(part.value)
        }
    }
}

@Composable
private fun RatingBody(summary: RatingSummary) {
    val average = summary.average
    if (average == null) {
        Text(stringResource(R.string.me_rating_none), style = MaterialTheme.typography.bodyMedium)
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(average.toPlainString(), style = MaterialTheme.typography.headlineMedium)
    }
    Text(
        pluralStringResource(R.plurals.me_rating_count, summary.count, summary.count),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val distribution = summary.distribution
    if (distribution == null) {
        Text(
            stringResource(R.string.me_rating_distribution_later),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val most = distribution.values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    distribution.forEach { (stars, number) ->
        val starsLabel = pluralStringResource(R.plurals.me_rating_stars, stars, stars)
        if (isLargeFontScale()) {
            LabelValueRow(starsLabel, number.toString())
            LinearProgressIndicator(progress = { number.toFloat() / most }, modifier = Modifier.fillMaxWidth())
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(starsLabel, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                LinearProgressIndicator(progress = { number.toFloat() / most }, modifier = Modifier.weight(1f))
                Text(number.toString(), style = MaterialTheme.typography.titleSmall)
            }
        }
    }
    if (summary.tags.isNotEmpty()) {
        Text(
            stringResource(R.string.me_rating_tags_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        summary.tags.forEach { (tag, number) -> LabelValueRow(stringResource(tagLabel(tag)), number.toString()) }
    }
}

@Composable
private fun DocumentsCard(
    overview: AccountOverview,
    onOpenWebsite: () -> Unit,
    onRetry: () -> Unit,
) {
    LadyGoCard {
        Text(stringResource(R.string.me_documents_title), style = MaterialTheme.typography.titleMedium)
        when (val documents = overview.documents) {
            is Part.Failed -> SectionError(documents.message, onRetry)
            is Part.Loaded ->
                if (documents.value.isEmpty()) {
                    Text(stringResource(R.string.me_documents_none), style = MaterialTheme.typography.bodyMedium)
                } else {
                    documents.value.forEach { DocumentRow(it) }
                }
        }
        Text(stringResource(R.string.me_vehicle_title), style = MaterialTheme.typography.titleMedium)
        when (val vehicle = overview.vehicle) {
            is Part.Failed -> SectionError(vehicle.message, onRetry)
            is Part.Loaded -> VehicleRow(vehicle.value)
        }
        Text(
            stringResource(R.string.me_changes_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PrimaryButton(text = stringResource(R.string.me_website_action), onClick = onOpenWebsite, modifier = Modifier.testTag("me_website"))
    }
}

@Composable
private fun DocumentRow(item: DocumentItem) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val label = stringResource(documentLabel(item.type))
        if (isLargeFontScale()) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            ReviewChip(item.status)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                ReviewChip(item.status)
            }
        }
        item.note?.let { InfoBanner(text = it, kind = BannerKind.WARNING) }
    }
}

@Composable
private fun VehicleRow(vehicle: VehicleInfo?) {
    if (vehicle == null) {
        Text(stringResource(R.string.me_vehicle_none), style = MaterialTheme.typography.bodyMedium)
        return
    }
    val details: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(vehicle.makeAndModel ?: stringResource(R.string.me_vehicle_unnamed), style = MaterialTheme.typography.bodyLarge)
            vehicle.plate?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
        }
    }
    if (isLargeFontScale()) {
        details(Modifier)
        ReviewChip(vehicle.status)
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            details(Modifier.weight(1f))
            ReviewChip(vehicle.status)
        }
    }
}

@Composable
private fun ReviewChip(status: ReviewStatus) {
    val (text, kind) =
        when (status) {
            ReviewStatus.APPROVED -> R.string.me_status_approved to BannerKind.SUCCESS
            ReviewStatus.PENDING -> R.string.me_status_pending to BannerKind.WARNING
            ReviewStatus.REJECTED -> R.string.me_status_rejected to BannerKind.ERROR
            ReviewStatus.SUSPENDED -> R.string.me_status_suspended to BannerKind.ERROR
            ReviewStatus.UNKNOWN -> R.string.me_status_unknown to BannerKind.INFO
        }
    StatusChip(text = stringResource(text), kind = kind)
}

private fun tagLabel(tag: DriverTag): Int =
    when (tag) {
        DriverTag.POLITE -> R.string.me_tag_polite
        DriverTag.SAFE_DRIVING -> R.string.me_tag_safe_driving
        DriverTag.CLEAN_VEHICLE -> R.string.me_tag_clean_vehicle
        DriverTag.ON_TIME -> R.string.me_tag_on_time
    }

private fun documentLabel(type: DriverDocumentResponse.Type?): Int =
    when (type) {
        DriverDocumentResponse.Type.GOVERNMENT_ID -> R.string.doc_government_id
        DriverDocumentResponse.Type.LICENSE -> R.string.doc_license
        DriverDocumentResponse.Type.VEHICLE_REGISTRATION -> R.string.doc_vehicle_registration
        DriverDocumentResponse.Type.VEHICLE_PHOTO -> R.string.doc_vehicle_photo
        DriverDocumentResponse.Type.INSURANCE -> R.string.doc_insurance
        DriverDocumentResponse.Type.FITNESS_CERTIFICATE -> R.string.doc_fitness_certificate
        DriverDocumentResponse.Type.POLLUTION_CERTIFICATE -> R.string.doc_pollution_certificate
        DriverDocumentResponse.Type.POLICE_VERIFICATION -> R.string.doc_police_verification
        DriverDocumentResponse.Type.PERMIT -> R.string.doc_permit
        DriverDocumentResponse.Type.PHOTO -> R.string.doc_photo
        DriverDocumentResponse.Type.SELFIE -> R.string.doc_selfie
        DriverDocumentResponse.Type.OTHER, null -> R.string.doc_other
    }
