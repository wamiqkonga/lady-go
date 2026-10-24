package com.ladygo.driver.earnings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ladygo.driver.R
import com.ladygo.driver.api.generated.models.DriverEarningsResponse
import com.ladygo.driver.api.generated.models.EarningsDayResponse
import com.ladygo.driver.api.generated.models.EarningsTotalsResponse
import com.ladygo.driver.earnings.EarningsUiState
import com.ladygo.driver.earnings.EarningsViewModel
import com.ladygo.driver.earnings.WeekChoice
import com.ladygo.driver.ui.components.BannerKind
import com.ladygo.driver.ui.components.InfoBanner
import com.ladygo.driver.ui.components.LabelValueRow
import com.ladygo.driver.ui.components.LadyGoCard
import com.ladygo.driver.ui.components.LadyGoTabScreen
import com.ladygo.driver.ui.components.LoadingState
import com.ladygo.driver.ui.components.Money
import com.ladygo.driver.ui.components.PrimaryButton
import com.ladygo.driver.ui.components.SectionLabel
import com.ladygo.driver.ui.components.StateIllustration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun EarningsScreen(viewModel: EarningsViewModel = hiltViewModel()) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    EarningsContent(
        state = state,
        onSelectWeek = viewModel::onSelectWeek,
        onRefresh = viewModel::onRefresh,
        onRetry = viewModel::onRetry,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EarningsContent(
    state: EarningsUiState,
    onSelectWeek: (WeekChoice) -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
) {
    LadyGoTabScreen(title = stringResource(R.string.earnings_title)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WeekChip(R.string.earnings_this_week, state.week == WeekChoice.THIS_WEEK) { onSelectWeek(WeekChoice.THIS_WEEK) }
            WeekChip(R.string.earnings_last_week, state.week == WeekChoice.LAST_WEEK) { onSelectWeek(WeekChoice.LAST_WEEK) }
        }
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val earnings = state.earnings
                when {
                    state.isLoading -> LoadingState(message = stringResource(R.string.earnings_loading))
                    earnings == null -> ErrorState(state.error, onRetry)
                    else -> {
                        if (state.refreshError != null) {
                            InfoBanner(text = stringResource(R.string.earnings_refresh_failed), kind = BannerKind.WARNING)
                        }
                        EarningsBody(state, earnings)
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekChip(
    label: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(stringResource(label)) })
}

@Composable
private fun ErrorState(
    message: String?,
    onRetry: () -> Unit,
) {
    StateIllustration(
        icon = Icons.Filled.ErrorOutline,
        title = stringResource(R.string.earnings_error_title),
        body = message,
    )
    PrimaryButton(text = stringResource(R.string.common_retry), onClick = onRetry)
}

@Composable
private fun EarningsBody(
    state: EarningsUiState,
    earnings: DriverEarningsResponse,
) {
    val totals = earnings.totals
    if ((totals?.rides ?: 0L) == 0L) {
        StateIllustration(
            icon = Icons.Filled.AccountBalanceWallet,
            title = stringResource(R.string.earnings_empty_title),
            body = stringResource(R.string.earnings_empty_body),
        )
        return
    }
    val dateFormat = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    Text(
        stringResource(R.string.earnings_range, state.range.from.format(dateFormat), state.range.to.format(dateFormat)),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TotalsCard(totals)
    val days = earnings.days.orEmpty()
    if (days.isNotEmpty()) {
        SectionLabel(stringResource(R.string.earnings_by_day))
        days.forEach { DayRow(it, totals?.currency) }
    }
}

@Composable
private fun TotalsCard(totals: EarningsTotalsResponse?) {
    val currency = totals?.currency
    val gross = totals?.gross ?: 0L
    val commissionSet = totals?.commissionConfigured == true
    // With no commission rate configured the server's net is the gross; never show anything else as net.
    val net = if (commissionSet) totals?.net ?: gross else gross
    LadyGoCard {
        Text(stringResource(R.string.earnings_net_label), style = MaterialTheme.typography.labelLarge)
        Text(
            Money.format(net, currency),
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.testTag("earnings_net"),
        )
        LabelValueRow(stringResource(R.string.earnings_gross_label), Money.format(gross, currency))
        LabelValueRow(
            stringResource(R.string.earnings_commission_label),
            if (commissionSet) Money.format(totals?.commission ?: 0L, currency) else stringResource(R.string.earnings_commission_unset),
        )
        LabelValueRow(stringResource(R.string.earnings_rides_label), (totals?.rides ?: 0L).toString())
        LabelValueRow(stringResource(R.string.earnings_cash_label), Money.format(totals?.cashCollected ?: 0L, currency))
        LabelValueRow(stringResource(R.string.earnings_online_label), Money.format(totals?.onlineCollected ?: 0L, currency))
    }
}

@Composable
private fun DayRow(
    day: EarningsDayResponse,
    fallbackCurrency: String?,
) {
    val currency = day.currency ?: fallbackCurrency
    val rides = (day.rides ?: 0L).toInt()
    val net = if (day.commissionConfigured == true) day.net ?: day.gross ?: 0L else day.gross ?: 0L
    LadyGoCard(modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(formatDay(day.date), style = MaterialTheme.typography.titleSmall)
                Text(
                    pluralStringResource(R.plurals.earnings_ride_count, rides, rides),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(Money.format(net, currency), style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** The server's ISO date as "Mon 20 Oct"; an unparseable value is shown as sent rather than hidden. */
internal fun formatDay(isoDate: String?): String {
    val date = isoDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return isoDate.orEmpty()
    return date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
}
