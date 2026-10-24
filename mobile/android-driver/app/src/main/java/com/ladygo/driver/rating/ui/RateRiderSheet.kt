package com.ladygo.driver.rating.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ladygo.driver.R
import com.ladygo.driver.rating.RateResult
import com.ladygo.driver.rating.RateRiderUiState
import com.ladygo.driver.rating.RateRiderViewModel
import com.ladygo.driver.rating.RiderRatingTag
import com.ladygo.driver.ui.components.BannerKind
import com.ladygo.driver.ui.components.InfoBanner
import com.ladygo.driver.ui.components.PrimaryButton
import com.ladygo.driver.ui.components.QuietButton

const val RATE_SHEET_TAG = "rate_rider_sheet"

/**
 * Shows the rating prompt over whatever screen the driver is on. [visible] is false while an incoming offer or an
 * active ride owns the screen; the prompt is kept and comes back when she is on a resting screen again. Swiping the
 * sheet away or tapping outside it is the same as Skip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RateRiderHost(
    viewModel: RateRiderViewModel,
    visible: Boolean,
) {
    val state = viewModel.prompt.collectAsStateWithLifecycle().value
    if (state == null || !visible) return
    ModalBottomSheet(
        onDismissRequest = viewModel::onSkip,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        RateRiderContent(
            state = state,
            onScore = viewModel::onScore,
            onToggleTag = viewModel::onToggleTag,
            onSubmit = viewModel::onSubmit,
            onSkip = viewModel::onSkip,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RateRiderContent(
    state: RateRiderUiState,
    onScore: (Int) -> Unit,
    onToggleTag: (RiderRatingTag) -> Unit,
    onSubmit: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .testTag(RATE_SHEET_TAG)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.rate_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        val result = state.result
        if (result == RateResult.Thanks) {
            InfoBanner(text = stringResource(R.string.rate_thanks), kind = BannerKind.SUCCESS)
            return@Column
        }
        if (result != null && result.isFinal) {
            FinalMessage(result)
            QuietButton(text = stringResource(R.string.rate_close), onClick = onSkip)
            return@Column
        }
        Text(
            stringResource(R.string.rate_prompt),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Stars(score = state.score, enabled = !state.isSubmitting, onScore = onScore)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RiderRatingTag.entries.forEach { tag ->
                FilterChip(
                    selected = tag in state.tags,
                    onClick = { onToggleTag(tag) },
                    enabled = !state.isSubmitting,
                    label = { Text(stringResource(tagLabel(tag))) },
                )
            }
        }
        if (result == RateResult.NetworkFailure) {
            InfoBanner(text = stringResource(R.string.rate_network_failure), kind = BannerKind.ERROR)
        }
        PrimaryButton(
            text = stringResource(if (result == RateResult.NetworkFailure) R.string.common_retry else R.string.rate_submit),
            onClick = onSubmit,
            enabled = state.score > 0,
            loading = state.isSubmitting,
        )
        QuietButton(text = stringResource(R.string.rate_skip), onClick = onSkip, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun FinalMessage(result: RateResult) {
    when (result) {
        RateResult.AlreadyRated -> InfoBanner(text = stringResource(R.string.rate_already_rated), kind = BannerKind.INFO)
        is RateResult.Rejected -> InfoBanner(text = result.message, kind = BannerKind.ERROR)
        else -> Unit
    }
}

@Composable
private fun Stars(
    score: Int,
    enabled: Boolean,
    onScore: (Int) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        for (star in 1..STAR_COUNT) {
            IconButton(onClick = { onScore(star) }, enabled = enabled, modifier = Modifier.testTag("rate_star_$star")) {
                Icon(
                    imageVector = if (star <= score) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = pluralStringResource(R.plurals.rate_star_description, star, star),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private fun tagLabel(tag: RiderRatingTag): Int =
    when (tag) {
        RiderRatingTag.RESPECTFUL -> R.string.rate_tag_respectful
        RiderRatingTag.ON_TIME -> R.string.rate_tag_on_time
    }

private const val STAR_COUNT = 5
