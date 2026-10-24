package com.ladygo.driver.rating.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ladygo.driver.rating.RateResult
import com.ladygo.driver.rating.RateRiderUiState
import com.ladygo.driver.rating.RiderRatingTag
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class RateRiderContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val id = UUID.randomUUID()

    private class Taps {
        val scores = mutableListOf<Int>()
        val tags = mutableListOf<RiderRatingTag>()
        var submits = 0
        var skips = 0
    }

    private fun show(
        state: RateRiderUiState,
        taps: Taps = Taps(),
    ) = composeRule.setContent {
        RateRiderContent(
            state = state,
            onScore = { taps.scores += it },
            onToggleTag = { taps.tags += it },
            onSubmit = { taps.submits++ },
            onSkip = { taps.skips++ },
        )
    }

    @Test
    fun showsFiveStarsTheTwoTagsAndNoCommentField() {
        show(RateRiderUiState(id))

        (1..5).forEach { composeRule.onNodeWithContentDescription(if (it == 1) "1 star" else "$it stars").assertExists() }
        composeRule.onNodeWithText("Respectful").assertExists()
        composeRule.onNodeWithText("On time").assertExists()
        composeRule.onNodeWithText("Comment").assertDoesNotExist()
    }

    @Test
    fun submitIsDisabledUntilAStarIsChosen() {
        show(RateRiderUiState(id))
        composeRule.onNodeWithText("Submit rating").assertIsNotEnabled()
    }

    @Test
    fun tappingStarsTagsSubmitAndSkipReportsEach() {
        val taps = Taps()
        show(RateRiderUiState(id, score = 4), taps)

        composeRule.onNodeWithContentDescription("5 stars").performClick()
        composeRule.onNodeWithText("Respectful").performClick()
        composeRule.onNodeWithText("Submit rating").assertIsEnabled().performClick()
        composeRule.onNodeWithText("Skip").performClick()

        assertEquals(listOf(5), taps.scores)
        assertEquals(listOf(RiderRatingTag.RESPECTFUL), taps.tags)
        assertEquals(1, taps.submits)
        assertEquals(1, taps.skips)
    }

    @Test
    fun savingDisablesTheSubmitButton() {
        show(RateRiderUiState(id, score = 4, isSubmitting = true))

        composeRule.onNodeWithText("Skip").assertExists()
        composeRule.onNodeWithText("Submit rating").assertIsNotEnabled() // double-tap guard in the UI too
    }

    @Test
    fun thanksReplacesTheControls() {
        show(RateRiderUiState(id, score = 4, result = RateResult.Thanks))

        composeRule.onNodeWithText("Thanks for rating your rider.").assertExists()
        composeRule.onNodeWithText("Skip").assertDoesNotExist()
    }

    @Test
    fun alreadyRatedShowsTheExactMessageAndOnlyClose() {
        val taps = Taps()
        show(RateRiderUiState(id, score = 4, result = RateResult.AlreadyRated), taps)

        composeRule.onNodeWithText("You already rated this ride").assertExists()
        composeRule.onNodeWithText("Submit rating").assertDoesNotExist()
        composeRule.onNodeWithText("Close").performClick()
        assertEquals(1, taps.skips)
    }

    @Test
    fun aRejectionShowsTheServersRealMessage() {
        show(RateRiderUiState(id, score = 2, result = RateResult.Rejected("The rating window for this ride has closed.")))

        composeRule.onNodeWithText("The rating window for this ride has closed.").assertExists()
        composeRule.onNodeWithText("Close").assertExists()
    }

    @Test
    fun aNetworkFailureOffersRetryAndSkip() {
        val taps = Taps()
        show(RateRiderUiState(id, score = 3, result = RateResult.NetworkFailure), taps)

        composeRule.onNodeWithText("Could not reach the server. Retry, or skip for now.").assertExists()
        composeRule.onNodeWithText("Retry").performClick()
        composeRule.onNodeWithText("Skip").performClick()
        assertEquals(1, taps.submits)
        assertEquals(1, taps.skips)
    }

    @Test
    fun theChosenStarIsMarkedSelectedForScreenReaders() {
        show(RateRiderUiState(id, score = 4))

        composeRule.onNodeWithTag("rate_star_4").assertIsSelected()
        composeRule.onNodeWithTag("rate_star_5").assertIsNotSelected()
        composeRule.onNodeWithTag("rate_star_1").assertIsNotSelected()
    }
}
