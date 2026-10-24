package com.ladygo.driver.earnings.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.ladygo.driver.api.generated.models.DriverEarningsResponse
import com.ladygo.driver.api.generated.models.EarningsDayResponse
import com.ladygo.driver.api.generated.models.EarningsTotalsResponse
import com.ladygo.driver.earnings.DateRange
import com.ladygo.driver.earnings.EarningsUiState
import com.ladygo.driver.testing.WithFontScale
import com.ladygo.driver.testing.assertNoMidWordBreak
import com.ladygo.driver.testing.assertSideBySideWith
import com.ladygo.driver.testing.assertStackedAbove
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp")
class EarningsLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val state =
        EarningsUiState(
            range = DateRange(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25)),
            isLoading = false,
            earnings =
                DriverEarningsResponse(
                    totals = EarningsTotalsResponse(rides = 3, gross = 45_000, currency = "INR", commissionConfigured = false),
                    days = listOf(EarningsDayResponse(date = "2026-10-20", rides = 1, gross = 15_000, currency = "INR")),
                ),
        )

    private fun show(fontScale: Float) =
        composeRule.setContent {
            WithFontScale(fontScale) { EarningsContent(state = state, onSelectWeek = {}, onRefresh = {}, onRetry = {}) }
        }

    @Test
    fun atTwoHundredPercentEveryLabelSitsAboveItsValueAndNoWordIsSplit() {
        show(2f)

        listOf("Fares collected", "Lady Go commission", "Rides", "Cash collected", "Paid online").forEach {
            composeRule.onNodeWithText(it).assertNoMidWordBreak()
        }
        composeRule.onNodeWithText("Commission not set yet").assertNoMidWordBreak()
        composeRule.onNodeWithText("Lady Go commission").assertStackedAbove(composeRule.onNodeWithText("Commission not set yet"))
        composeRule.onNodeWithText("Rides").assertStackedAbove(composeRule.onNodeWithText("3", substring = false))
    }

    @Test
    fun atTwoHundredPercentTheDayCardStacksDateAndAmount() {
        show(2f)

        composeRule.onNodeWithText("1 ride").assertStackedAbove(composeRule.onNodeWithText("₹150"))
    }

    @Test
    fun atDefaultScaleTheRowsKeepTwoColumns() {
        show(1f)

        composeRule.onNodeWithText("Lady Go commission").assertSideBySideWith(composeRule.onNodeWithText("Commission not set yet"))
        composeRule.onNodeWithText("Fares collected").assertSideBySideWith(composeRule.onAllNodesWithText("₹450")[1])
    }
}
