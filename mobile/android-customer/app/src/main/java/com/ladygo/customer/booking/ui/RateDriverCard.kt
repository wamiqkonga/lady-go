package com.ladygo.customer.booking.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ladygo.customer.R
import com.ladygo.customer.api.generated.models.BookingDetailResponse
import com.ladygo.customer.api.generated.models.SubmitRatingRequest
import com.ladygo.customer.booking.RateDriverViewModel
import com.ladygo.customer.booking.RatingStatus
import com.ladygo.customer.ui.components.BannerKind
import com.ladygo.customer.ui.components.InfoBanner
import com.ladygo.customer.ui.components.PrimaryButton

/** The real card: shown on the finished ride, where it rates the driver of [booking]. */
@Composable
fun RateDriverCard(
    booking: BookingDetailResponse,
    viewModel: RateDriverViewModel = hiltViewModel(),
) {
    // A ride with no driver (cancelled search) has nobody to rate.
    if (booking.driver == null) return
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    RateDriverContent(status = state.status, onSubmit = viewModel::onSubmit)
}

private val RIDER_TAGS =
    listOf(
        SubmitRatingRequest.Tags.POLITE to R.string.rate_tag_polite,
        SubmitRatingRequest.Tags.SAFE_DRIVING to R.string.rate_tag_safe_driving,
        SubmitRatingRequest.Tags.CLEAN_VEHICLE to R.string.rate_tag_clean_vehicle,
        SubmitRatingRequest.Tags.ON_TIME to R.string.rate_tag_on_time,
    )

private const val STAR_COUNT = 5

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RateDriverContent(
    status: RatingStatus,
    onSubmit: (score: Int, tags: List<SubmitRatingRequest.Tags>, comment: String) -> Unit,
) {
    if (status == RatingStatus.DONE) {
        InfoBanner(text = stringResource(R.string.rate_thanks), kind = BannerKind.SUCCESS, modifier = Modifier.testTag("rate_done"))
        return
    }
    var score by rememberSaveable { mutableIntStateOf(0) }
    var tags by rememberSaveable { mutableStateOf(emptySet<SubmitRatingRequest.Tags>()) }
    var comment by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().testTag("rate_card")) {
        Text(stringResource(R.string.rate_title), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (value in 1..STAR_COUNT) {
                val description = stringResource(R.string.rate_star_description, value)
                IconButton(
                    onClick = { score = value },
                    modifier = Modifier.semantics { contentDescription = description }.testTag("rate_star_$value"),
                ) {
                    Icon(
                        imageVector = if (value <= score) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        if (score > 0) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RIDER_TAGS.forEach { (tag, label) ->
                    FilterChip(
                        selected = tag in tags,
                        onClick = { tags = if (tag in tags) tags - tag else tags + tag },
                        label = { Text(stringResource(label)) },
                    )
                }
            }
            OutlinedTextField(
                value = comment,
                onValueChange = { comment = it.take(RateDriverViewModel.MAX_COMMENT_LENGTH) },
                label = { Text(stringResource(R.string.rate_comment_label)) },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().testTag("rate_comment"),
            )
        }
        if (status == RatingStatus.FAILED) {
            InfoBanner(text = stringResource(R.string.rate_error), kind = BannerKind.ERROR, modifier = Modifier.padding(top = 4.dp))
        }
        PrimaryButton(
            text = stringResource(R.string.rate_submit),
            onClick = { onSubmit(score, tags.toList(), comment) },
            enabled = score > 0 && status != RatingStatus.SUBMITTING,
            loading = status == RatingStatus.SUBMITTING,
        )
    }
}
