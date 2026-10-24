package com.ladygo.driver.history.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ladygo.driver.history.RideHistoryStatus
import com.ladygo.driver.history.RideHistoryUiState
import com.ladygo.driver.history.rideItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class RideHistoryContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun show(
        state: RideHistoryUiState,
        onRetry: () -> Unit = {},
        onLoadMore: () -> Unit = {},
        onOpenRide: (UUID) -> Unit = {},
    ) = composeRule.setContent {
        RideHistoryContent(state = state, onRefresh = {}, onRetry = onRetry, onLoadMore = onLoadMore, onOpenRide = onOpenRide)
    }

    @Test
    fun loading() {
        show(RideHistoryUiState(isLoading = true))
        composeRule.onNodeWithText("Loading your rides...").assertExists()
    }

    @Test
    fun empty() {
        show(RideHistoryUiState(isLoading = false))
        composeRule.onNodeWithText("No rides yet").assertExists()
        composeRule.onNodeWithText("Go online to get your first ride.").assertExists()
    }

    @Test
    fun errorShowsTheServerMessageAndRetries() {
        var retried = 0
        show(RideHistoryUiState(isLoading = false, error = "Server says no"), onRetry = { retried++ })

        composeRule.onNodeWithText("Server says no").assertExists()
        composeRule.onNodeWithText("Retry").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun rowsShowRouteFareStatusRatingAndTheRiderRatedHint() {
        val ride = rideItem(myRating = 4, ratedByRider = true)
        show(RideHistoryUiState(items = listOf(ride), isLoading = false))

        composeRule.onNodeWithText("12 Lal Chowk").assertExists()
        composeRule.onNodeWithText("Dal Gate").assertExists()
        composeRule.onNodeWithText("₹185").assertExists()
        composeRule.onNodeWithText("Completed").assertExists()
        composeRule.onNodeWithText("You rated 4").assertExists()
        composeRule.onNodeWithText("Rider has rated").assertExists()
    }

    @Test
    fun aRowWithoutRatingsShowsNeitherHint() {
        show(RideHistoryUiState(items = listOf(rideItem()), isLoading = false))
        composeRule.onNodeWithText("Rider has rated").assertDoesNotExist()
        composeRule.onNodeWithText("You rated 4").assertDoesNotExist()
    }

    @Test
    fun aHiddenAddressSaysSoAndCancelledAndEndedEarlyHaveTheirOwnChips() {
        show(
            RideHistoryUiState(
                items =
                    listOf(
                        rideItem(status = RideHistoryStatus.CANCELLED, pickup = null, destination = null, fare = null),
                        rideItem(status = RideHistoryStatus.ENDED_EARLY),
                    ),
                isLoading = false,
            ),
        )

        composeRule.onNodeWithText("Cancelled").assertExists()
        composeRule.onNodeWithText("Ended early").assertExists()
        composeRule.onAllNodesWithText("Address hidden for this ride", useUnmergedTree = true).assertCountEquals(2)
    }

    @Test
    fun tappingARowOpensThatRide() {
        val ride = rideItem()
        var opened: UUID? = null
        show(RideHistoryUiState(items = listOf(ride), isLoading = false), onOpenRide = { opened = it })

        composeRule.onNodeWithText("Dal Gate").performClick()
        assertEquals(ride.bookingId, opened)
    }

    @Test
    fun aRefreshFailureBannerKeepsTheRows() {
        show(RideHistoryUiState(items = listOf(rideItem()), isLoading = false, refreshError = "x"))
        composeRule.onNodeWithText("Could not refresh. Showing the rides we loaded earlier.").assertExists()
        composeRule.onNodeWithText("Dal Gate").assertExists()
    }

    @Test
    fun aFailedNextPageShowsARetryThatAsksForItAgain() {
        var loads = 0
        show(
            RideHistoryUiState(items = listOf(rideItem()), isLoading = false, hasMore = true, loadMoreError = "x"),
            onLoadMore = { loads++ },
        )
        val before = loads
        composeRule.onNodeWithText("Could not load more rides.").assertExists()
        composeRule.onNodeWithText("Retry").performClick()
        assertEquals(before + 1, loads)
    }

    @Test
    fun theNextPageSpinnerShowsWhileLoadingMore() {
        show(RideHistoryUiState(items = listOf(rideItem()), isLoading = false, hasMore = true, isLoadingMore = true))
        composeRule.onNodeWithText("Loading more rides...").assertExists()
    }
}
