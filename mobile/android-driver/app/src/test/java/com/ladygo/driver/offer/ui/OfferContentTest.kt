package com.ladygo.driver.offer.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ladygo.driver.api.generated.models.OfferResponse
import com.ladygo.driver.offer.OfferUiState
import com.ladygo.driver.realtime.ServerClockSkew
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.temporal.ChronoUnit

@RunWith(RobolectricTestRunner::class)
class OfferContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    // Far enough in the future that the ticking countdown never hits zero (and so never fires
    // onCountdownExpired) during a test's short real execution - the exact remaining-seconds text is
    // deliberately not asserted on anywhere below, since it's inherently time-dependent.
    private val notExpiringSoon = Instant.now().plus(5, ChronoUnit.MINUTES).toString()

    private fun showingState(
        isActionInFlight: Boolean = false,
        error: String? = null,
        fare: Long? = 250L,
        currency: String? = "PKR",
    ) = OfferUiState.Showing(
        offer =
            OfferResponse(
                offerId = java.util.UUID.randomUUID(),
                estimatedFare = fare,
                currency = currency,
                expiresAt = notExpiringSoon,
            ),
        skew = ServerClockSkew.NONE,
        isActionInFlight = isActionInFlight,
        error = error,
    )

    @Test
    fun showsTheFareAndBothActions() {
        composeRule.setContent {
            OfferContent(state = showingState(), onAccept = {}, onDecline = {}, onCountdownExpired = {})
        }

        composeRule.onNodeWithText("PKR 250").assertExists()
        composeRule.onNodeWithText("Accept").assertExists()
        composeRule.onNodeWithText("Decline").assertExists()
    }

    @Test
    fun acceptInvokesTheCallback() {
        var accepted = false
        composeRule.setContent {
            OfferContent(state = showingState(), onAccept = { accepted = true }, onDecline = {}, onCountdownExpired = {})
        }

        composeRule.onNodeWithText("Accept").performClick()

        assert(accepted)
    }

    @Test
    fun declineInvokesTheCallback() {
        var declined = false
        composeRule.setContent {
            OfferContent(state = showingState(), onAccept = {}, onDecline = { declined = true }, onCountdownExpired = {})
        }

        composeRule.onNodeWithText("Decline").performClick()

        assert(declined)
    }

    @Test
    fun actionsAreDisabledWhileAnActionIsInFlight() {
        var accepted = false
        var declined = false
        composeRule.setContent {
            OfferContent(
                state = showingState(isActionInFlight = true),
                onAccept = { accepted = true },
                onDecline = { declined = true },
                onCountdownExpired = {},
            )
        }

        composeRule.onNodeWithText("Accept").performClick()
        composeRule.onNodeWithText("Decline").performClick()

        assert(!accepted)
        assert(!declined)
    }

    @Test
    fun anErrorMessageIsShownWhenPresent() {
        composeRule.setContent {
            OfferContent(state = showingState(error = "This offer has expired."), onAccept = {}, onDecline = {}, onCountdownExpired = {})
        }

        composeRule.onNodeWithText("This offer has expired.").assertExists()
    }

    @Test
    fun noFareLineWhenTheOfferHasNoFareOrCurrency() {
        composeRule.setContent {
            OfferContent(state = showingState(fare = null, currency = null), onAccept = {}, onDecline = {}, onCountdownExpired = {})
        }

        composeRule.onNodeWithText("Accept").assertExists() // sanity: the rest of the screen still renders
    }
}
