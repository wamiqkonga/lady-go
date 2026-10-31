package com.ladygo.customer.booking.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.ladygo.customer.api.generated.models.SubmitRatingRequest
import com.ladygo.customer.booking.RatingStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RateDriverContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sendIsDisabledUntilAStarIsPicked() {
        composeRule.setContent { RateDriverContent(RatingStatus.IDLE) { _, _, _ -> } }

        composeRule.onNodeWithText("Send rating").assertIsNotEnabled()
        composeRule.onNodeWithTag("rate_star_4").performClick()
        composeRule.onNodeWithText("Send rating").assertIsEnabled()
    }

    @Test
    fun theScoreTagsAndCommentAreSent() {
        var sent: Triple<Int, List<SubmitRatingRequest.Tags>, String>? = null
        composeRule.setContent { RateDriverContent(RatingStatus.IDLE) { s, t, c -> sent = Triple(s, t, c) } }

        composeRule.onNodeWithTag("rate_star_5").performClick()
        composeRule.onNodeWithText("Polite").performClick()
        composeRule.onNodeWithText("On time").performClick()
        composeRule.onNodeWithTag("rate_comment").performTextInput("Lovely ride")
        composeRule.onNodeWithText("Send rating").performClick()

        assertEquals(
            Triple(5, listOf(SubmitRatingRequest.Tags.POLITE, SubmitRatingRequest.Tags.ON_TIME), "Lovely ride"),
            sent,
        )
    }

    @Test
    fun aTagCanBeUnpicked() {
        var tags: List<SubmitRatingRequest.Tags>? = null
        composeRule.setContent { RateDriverContent(RatingStatus.IDLE) { _, t, _ -> tags = t } }

        composeRule.onNodeWithTag("rate_star_3").performClick()
        composeRule.onNodeWithText("Polite").performClick()
        composeRule.onNodeWithText("Polite").performClick()
        composeRule.onNodeWithText("Send rating").performClick()

        assertEquals(emptyList<SubmitRatingRequest.Tags>(), tags)
    }

    @Test
    fun onceSentItThanksHerAndOffersNoSecondRating() {
        composeRule.setContent { RateDriverContent(RatingStatus.DONE) { _, _, _ -> } }

        composeRule.onNodeWithTag("rate_done").assertExists()
        composeRule.onNodeWithTag("rate_card").assertDoesNotExist()
    }

    @Test
    fun aFailureShowsTheErrorAndKeepsTheFormForARetry() {
        composeRule.setContent { RateDriverContent(RatingStatus.FAILED) { _, _, _ -> } }

        composeRule.onNodeWithText("We could not send your rating. Please try again.").assertExists()
        composeRule.onNodeWithTag("rate_card").assertExists()
    }
}
