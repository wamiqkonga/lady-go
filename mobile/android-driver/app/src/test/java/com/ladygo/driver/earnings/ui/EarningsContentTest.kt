package com.ladygo.driver.earnings.ui

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ladygo.driver.api.generated.models.DriverEarningsResponse
import com.ladygo.driver.api.generated.models.EarningsDayResponse
import com.ladygo.driver.api.generated.models.EarningsTotalsResponse
import com.ladygo.driver.earnings.DateRange
import com.ladygo.driver.earnings.EarningsUiState
import com.ladygo.driver.earnings.WeekChoice
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class EarningsContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val range = DateRange(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25))

    private fun totals(commissionConfigured: Boolean) =
        EarningsTotalsResponse(
            rides = 3,
            gross = 45_000,
            commission = 6_750,
            net = if (commissionConfigured) 38_250 else 45_000,
            currency = "INR",
            cashCollected = 30_000,
            onlineCollected = 15_000,
            commissionConfigured = commissionConfigured,
        )

    private fun earnings(commissionConfigured: Boolean = true) =
        DriverEarningsResponse(
            totals = totals(commissionConfigured),
            days =
                listOf(
                    EarningsDayResponse(
                        date = "2026-10-20",
                        rides = 1,
                        gross = 15_000,
                        net = 12_750,
                        currency = "INR",
                        commissionConfigured = commissionConfigured,
                    ),
                ),
        )

    private fun show(
        state: EarningsUiState,
        onSelectWeek: (WeekChoice) -> Unit = {},
        onRetry: () -> Unit = {},
    ) = composeRule.setContent {
        EarningsContent(state = state, onSelectWeek = onSelectWeek, onRefresh = {}, onRetry = onRetry)
    }

    @Test
    fun loadingShowsTheSpinnerMessage() {
        show(EarningsUiState(range = range, isLoading = true))
        composeRule.onNodeWithText("Loading your earnings...").assertExists()
    }

    @Test
    fun successShowsNetAsTheHeadlineAndEveryBreakdownRow() {
        show(EarningsUiState(range = range, isLoading = false, earnings = earnings()))

        composeRule.onNodeWithText("₹382.50").assertExists()
        composeRule.onNodeWithText("₹450").assertExists() // gross
        composeRule.onNodeWithText("₹67.50").assertExists() // commission
        composeRule.onNodeWithText("₹300").assertExists() // cash
        composeRule.onNodeWithText("₹150", substring = false).assertExists() // online
        composeRule.onNodeWithText("3").assertExists()
        composeRule.onNodeWithText("1 ride").assertExists()
        composeRule.onNodeWithText("₹127.50").assertExists() // the day's net
    }

    @Test
    fun anUnsetCommissionSaysSoAndNetEqualsGross() {
        show(EarningsUiState(range = range, isLoading = false, earnings = earnings(commissionConfigured = false)))

        composeRule.onNodeWithText("Commission not set yet").assertExists()
        composeRule.onNodeWithText("₹67.50").assertDoesNotExist()
        composeRule.onNodeWithTag("earnings_net").assertTextEquals("₹450")
    }

    @Test
    fun noRidesShowsTheEmptyState() {
        show(
            EarningsUiState(
                range = range,
                isLoading = false,
                earnings = DriverEarningsResponse(totals = EarningsTotalsResponse(rides = 0)),
            ),
        )

        composeRule.onNodeWithText("No earnings yet").assertExists()
        composeRule.onNodeWithText("Go online to get your first ride.").assertExists()
    }

    @Test
    fun anErrorShowsTheServersMessageAndRetryWorks() {
        var retried = 0
        show(EarningsUiState(range = range, isLoading = false, error = "Server says no"), onRetry = { retried++ })

        composeRule.onNodeWithText("Server says no").assertExists()
        composeRule.onNodeWithText("Retry").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun aFailedRefreshKeepsTheNumbersAndShowsABanner() {
        show(EarningsUiState(range = range, isLoading = false, earnings = earnings(), refreshError = "offline"))

        composeRule.onNodeWithText("Could not refresh. Showing the last numbers we loaded.").assertExists()
        composeRule.onNodeWithText("₹382.50").assertExists()
    }

    @Test
    fun theWeekChipsReflectAndChangeTheSelection() {
        var chosen: WeekChoice? = null
        show(EarningsUiState(range = range, isLoading = false, earnings = earnings()), onSelectWeek = { chosen = it })

        composeRule.onNodeWithText("This week").assertIsSelected()
        composeRule.onNodeWithText("Last week").performClick()
        assertEquals(WeekChoice.LAST_WEEK, chosen)
    }
}
