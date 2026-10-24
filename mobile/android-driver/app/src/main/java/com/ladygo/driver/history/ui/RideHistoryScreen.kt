package com.ladygo.driver.history.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TripOrigin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ladygo.driver.R
import com.ladygo.driver.history.RideHistoryItem
import com.ladygo.driver.history.RideHistoryStatus
import com.ladygo.driver.history.RideHistoryUiState
import com.ladygo.driver.history.RideHistoryViewModel
import com.ladygo.driver.history.formatRideDate
import com.ladygo.driver.ui.components.BannerKind
import com.ladygo.driver.ui.components.InfoBanner
import com.ladygo.driver.ui.components.LadyGoCard
import com.ladygo.driver.ui.components.LadyGoTabScreen
import com.ladygo.driver.ui.components.LoadingState
import com.ladygo.driver.ui.components.Money
import com.ladygo.driver.ui.components.PrimaryButton
import com.ladygo.driver.ui.components.QuietButton
import com.ladygo.driver.ui.components.StateIllustration
import com.ladygo.driver.ui.components.StatusChip
import java.util.UUID

/** Start fetching the next page when the driver is within this many rows of the end. */
private const val LOAD_MORE_THRESHOLD = 3

@Composable
fun RideHistoryScreen(
    viewModel: RideHistoryViewModel,
    onOpenRide: (UUID) -> Unit,
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    RideHistoryContent(
        state = state,
        onRefresh = viewModel::onRefresh,
        onRetry = viewModel::onRetry,
        onLoadMore = viewModel::onLoadMore,
        onOpenRide = onOpenRide,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideHistoryContent(
    state: RideHistoryUiState,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenRide: (UUID) -> Unit,
) {
    LadyGoTabScreen(title = stringResource(R.string.rides_title)) {
        PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = onRefresh, modifier = Modifier.weight(1f)) {
            when {
                state.isLoading -> Message { LoadingState(message = stringResource(R.string.rides_loading)) }
                state.items.isEmpty() && state.error != null ->
                    Message {
                        StateIllustration(
                            icon = Icons.Filled.ErrorOutline,
                            title = stringResource(R.string.rides_error_title),
                            body = state.error,
                        )
                        PrimaryButton(text = stringResource(R.string.common_retry), onClick = onRetry)
                    }
                state.items.isEmpty() ->
                    Message {
                        StateIllustration(
                            icon = Icons.Filled.DirectionsCar,
                            title = stringResource(R.string.rides_empty_title),
                            body = stringResource(R.string.rides_empty_body),
                        )
                    }
                else -> RideList(state, onLoadMore, onOpenRide)
            }
        }
    }
}

/** Short states still scroll, so pull-to-refresh works on them and nothing clips at large font sizes. */
@Composable
private fun Message(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) { content() }
}

@Composable
private fun RideList(
    state: RideHistoryUiState,
    onLoadMore: () -> Unit,
    onOpenRide: (UUID) -> Unit,
) {
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= info.totalItemsCount - LOAD_MORE_THRESHOLD
        }
    }
    // Keyed on the row count and the loading flag too, so a page that leaves the driver still near the end asks for
    // the next one; a failed page waits for the Retry button instead of looping.
    LaunchedEffect(nearEnd, state.items.size, state.isLoadingMore, state.hasMore, state.loadMoreError) {
        if (nearEnd && state.hasMore && state.loadMoreError == null) onLoadMore()
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.refreshError != null) {
            item(key = "refresh-error") { InfoBanner(text = stringResource(R.string.rides_refresh_failed), kind = BannerKind.WARNING) }
        }
        items(state.items, key = { it.bookingId }) { ride -> RideRow(ride, onClick = { onOpenRide(ride.bookingId) }) }
        if (state.isLoadingMore) {
            item(key = "loading-more") { LoadingState(message = stringResource(R.string.rides_loading_more)) }
        }
        if (state.loadMoreError != null) {
            item(key = "load-more-error") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoBanner(text = stringResource(R.string.rides_load_more_failed), kind = BannerKind.ERROR)
                    QuietButton(text = stringResource(R.string.common_retry), onClick = onLoadMore)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RideRow(
    ride: RideHistoryItem,
    onClick: () -> Unit,
) {
    LadyGoCard(modifier = Modifier.clickable(role = Role.Button, onClick = onClick)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Text(
                formatRideDate(ride.completedAt) ?: stringResource(R.string.rides_date_unknown),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            RideStatusChip(ride.status)
        }
        RideRoute(ride)
        ride.fare?.let { Text(Money.format(it, ride.currency), style = MaterialTheme.typography.titleMedium) }
        if (ride.ratedByRider || ride.myRating != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ride.ratedByRider) {
                    Text(
                        stringResource(R.string.rides_rider_rated),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
                ride.myRating?.let { score ->
                    StatusChip(text = stringResource(R.string.rides_my_rating, score), kind = BannerKind.INFO, icon = Icons.Filled.Star)
                }
            }
        }
    }
}

@Composable
internal fun RideRoute(ride: RideHistoryItem) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RoutePoint(Icons.Filled.TripOrigin, ride.pickupAddress)
        RoutePoint(Icons.Filled.Place, ride.destinationAddress)
    }
}

@Composable
private fun RoutePoint(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    address: String?,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(address ?: stringResource(R.string.rides_address_hidden), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun RideStatusChip(status: RideHistoryStatus) {
    val (text, kind) =
        when (status) {
            RideHistoryStatus.COMPLETED -> R.string.rides_status_completed to BannerKind.SUCCESS
            RideHistoryStatus.CANCELLED -> R.string.rides_status_cancelled to BannerKind.ERROR
            RideHistoryStatus.ENDED_EARLY -> R.string.rides_status_ended_early to BannerKind.WARNING
            RideHistoryStatus.OTHER -> R.string.rides_status_other to BannerKind.INFO
        }
    StatusChip(text = stringResource(text), kind = kind)
}
