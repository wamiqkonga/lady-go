package com.ladygo.driver.ride.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.ladygo.driver.api.generated.models.BookingDetailResponse
import com.ladygo.driver.api.generated.models.BookingDetailResponse.PaymentMethod
import com.ladygo.driver.api.generated.models.BookingDetailResponse.Status
import com.ladygo.driver.ride.ActiveRideUiState
import com.ladygo.driver.ride.RideStage
import com.ladygo.driver.ride.RideStateMachine
import com.ladygo.driver.ride.SelfieState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class ActiveRideContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun state(
        status: Status,
        payment: PaymentMethod = PaymentMethod.CASH,
        inFlight: Boolean = false,
        error: String? = null,
        cashConfirmed: Boolean = false,
        selfie: SelfieState = SelfieState.Unavailable,
    ) = ActiveRideUiState(
        booking =
            BookingDetailResponse(
                id = UUID.randomUUID(),
                status = status,
                pickupAddress = "12 Lal Chowk",
                destinationAddress = "Dal Gate",
                paymentMethod = payment,
                estimatedFare = 250L,
                currency = "INR",
            ),
        stage = RideStateMachine.stageFor(status),
        isActionInFlight = inFlight,
        error = error,
        cashConfirmed = cashConfirmed,
        selfie = selfie,
    )

    private fun show(
        state: ActiveRideUiState,
        actions: ActiveRideActions = ActiveRideActions(),
    ) = composeRule.setContent { ActiveRideContent(state = state, actions = actions) }

    @Test
    fun toPickupShowsNavigateArrivedAndCall112ButNoStartOrComplete() {
        show(state(Status.DRIVER_ARRIVING))

        composeRule.onNodeWithText("Navigate").assertExists()
        composeRule.onNodeWithText("I've arrived").assertExists()
        composeRule.onNodeWithText("Call 112").assertExists()
        composeRule.onNodeWithText("Start ride").assertDoesNotExist()
        composeRule.onNodeWithText("Complete ride").assertDoesNotExist()
    }

    @Test
    fun startRideIsOnlyOfferedOnceArrivedAndPassesTheEnteredPin() {
        var pin: String? = null
        show(state(Status.DRIVER_ARRIVED), ActiveRideActions(onStartRide = { pin = it }))

        composeRule.onNodeWithTag("ride_pin_field").performTextInput("1234")
        composeRule.onNodeWithText("Start ride").performScrollTo().performClick()

        assertEquals("1234", pin)
    }

    @Test
    fun theArrivedStageOffersARetryWhenThePhotoIsUnavailable() {
        var retried = false
        show(state(Status.DRIVER_ARRIVED), ActiveRideActions(onRetrySelfie = { retried = true }))

        composeRule.onNodeWithText("Photo unavailable.").assertExists()
        composeRule.onNodeWithText("Try again").performClick()

        assertEquals(true, retried)
    }

    @Test
    fun inTripOffersCompleteAndEndEarlyAndTheEndEarlyDialogNeedsAReason() {
        var ended: Pair<String, Boolean>? = null
        show(state(Status.RIDE_STARTED), ActiveRideActions(onEndEarly = { r, s -> ended = r to s }))

        composeRule.onNodeWithText("Complete ride").assertExists()
        composeRule.onNodeWithText("End ride early").performScrollTo().performClick()
        composeRule.onNodeWithText("End ride").assertIsNotEnabled()
        composeRule.onNodeWithTag("end_early_reason").performTextInput("rider unwell")
        composeRule.onNodeWithText("I don't feel safe").performClick()
        composeRule.onNodeWithText("End ride").performClick()

        assertEquals("rider unwell" to true, ended)
    }

    @Test
    fun actionsAreDisabledWhileACallIsInFlight() {
        show(state(Status.RIDE_STARTED, inFlight = true))

        composeRule.onNodeWithText("Complete ride").assertIsNotEnabled()
    }

    @Test
    fun cashPaymentShowsConfirmUntilConfirmedThenTheNote() {
        var confirmed = false
        show(state(Status.PAYMENT_PENDING), ActiveRideActions(onConfirmCash = { confirmed = true }))

        composeRule.onNodeWithText("Cash collected").performClick()
        assertEquals(true, confirmed)
    }

    @Test
    fun aConfirmedCashPaymentShowsTheNoteAndAWayBackToHome() {
        var done = 0
        show(state(Status.PAYMENT_PENDING, cashConfirmed = true), ActiveRideActions(onDone = { done++ }))

        composeRule.onNodeWithText("Cash collected").assertDoesNotExist()
        composeRule.onNodeWithText("Call 112").assertDoesNotExist()
        composeRule.onNodeWithText("Back to home").performClick()
        assertEquals(1, done)
    }

    @Test
    fun anUnconfirmedCashPaymentOffersNoWayOutYet() {
        show(state(Status.PAYMENT_PENDING))

        composeRule.onNodeWithText("Back to home").assertDoesNotExist()
    }

    @Test
    fun onlinePaymentNeverOffersCashConfirmation() {
        show(state(Status.PAYMENT_PENDING, payment = PaymentMethod.ONLINE))

        composeRule.onNodeWithText("Cash collected").assertDoesNotExist()
        composeRule.onNodeWithText("Online payment is not available yet.").assertExists()
    }

    @Test
    fun theServersErrorIsShown() {
        show(state(Status.DRIVER_ARRIVING, error = "You are too far from the pickup point."))

        composeRule.onNodeWithTag("ride_error").assertExists()
        composeRule.onNodeWithText("You are too far from the pickup point.").assertExists()
    }

    @Test
    fun endedAndFinishedRidesOfferOnlyBackToHome() {
        var done = 0
        show(state(Status.CANCELLED), ActiveRideActions(onDone = { done++ }))

        composeRule.onNodeWithText("This ride is no longer active.").assertExists()
        composeRule.onNodeWithText("Back to home").performClick()
        composeRule.onNodeWithText("Navigate").assertDoesNotExist()
        assertEquals(1, done)
        assertEquals(RideStage.ENDED, state(Status.CANCELLED).stage)
    }
}
