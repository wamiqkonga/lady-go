package com.ladygo.customer.booking.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.ladygo.customer.api.generated.models.BookingDetailResponse
import com.ladygo.customer.api.generated.models.BookingDetailResponse.PaymentMethod
import com.ladygo.customer.api.generated.models.BookingDetailResponse.Status
import com.ladygo.customer.api.generated.models.DriverSummaryResponse
import com.ladygo.customer.booking.TrackUiState
import com.ladygo.customer.booking.stageFor
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class TrackBookingContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun state(
        status: Status,
        pin: String? = null,
        driver: DriverSummaryResponse? = null,
        isCancelling: Boolean = false,
        error: String? = null,
        payment: PaymentMethod = PaymentMethod.CASH,
    ) = TrackUiState(
        booking =
            BookingDetailResponse(
                id = UUID.randomUUID(),
                status = status,
                pickupAddress = "Lal Chowk",
                destinationAddress = "Dal Gate",
                paymentMethod = payment,
                estimatedFare = 250L,
                currency = "INR",
                ridePin = pin,
                driver = driver,
            ),
        stage = stageFor(status),
        isCancelling = isCancelling,
        error = error,
    )

    private fun show(
        state: TrackUiState,
        actions: TrackActions = TrackActions(),
    ) = composeRule.setContent { TrackBookingContent(state, actions) }

    @Test
    fun searchingShowsTheNoteAndAnEnabledCancelButNoPinOrCall112() {
        show(state(Status.SEARCHING_DRIVER))

        composeRule.onNodeWithText("We are looking for a driver near you.").assertExists()
        composeRule.onNodeWithText("Cancel ride").assertExists()
        composeRule.onNodeWithText("Call 112").assertDoesNotExist()
        composeRule.onNodeWithTag("track_pin").assertDoesNotExist()
    }

    @Test
    fun anAssignedDriverShowsTheDriverVehicleAndRidePinAndCall112() {
        show(
            state(
                Status.DRIVER_ARRIVING,
                pin = "4821",
                driver =
                    DriverSummaryResponse(
                        name = "Aisha",
                        vehicleColor = "White",
                        vehicleMake = "Maruti",
                        vehicleRegistration = "JK01AB1234",
                    ),
            ),
        )

        composeRule.onNodeWithText("Aisha").assertExists()
        composeRule.onNodeWithText("JK01AB1234").assertExists()
        composeRule.onNodeWithTag("track_pin").assertExists()
        composeRule.onNodeWithText("Call 112").assertExists()
    }

    @Test
    fun onceTheTripHasStartedThereIsNoCancelButCall112Remains() {
        show(state(Status.RIDE_STARTED))

        composeRule.onNodeWithText("Cancel ride").assertDoesNotExist()
        composeRule.onNodeWithText("Call 112").assertExists()
        composeRule.onNodeWithTag("track_pin").assertDoesNotExist()
    }

    @Test
    fun cancelAsksForConfirmationAndPassesTheReason() {
        var reason: String? = null
        show(state(Status.SEARCHING_DRIVER), TrackActions(onCancel = { reason = it }))

        composeRule.onNodeWithText("Cancel ride").performClick()
        composeRule.onNodeWithTag("cancel_reason").performTextInput("Plans changed")
        composeRule.onAllNodesWithText("Cancel ride")[1].performClick() // [0] is the screen button, [1] the dialog confirm

        assertEquals("Plans changed", reason)
    }

    @Test
    fun cancelIsDisabledWhileCancelling() {
        show(state(Status.SEARCHING_DRIVER, isCancelling = true))

        composeRule.onNodeWithText("Cancel ride").assertIsNotEnabled()
    }

    @Test
    fun aCashTripAsksToPayTheDriverAndOnlineDoesNot() {
        show(state(Status.PAYMENT_PENDING))
        composeRule.onNodeWithText("Please pay your driver the fare in cash.").assertExists()
    }

    @Test
    fun terminalRidesOfferOnlyBackToHome() {
        var done = 0
        show(state(Status.CANCELLED), TrackActions(onDone = { done++ }))

        composeRule.onNodeWithText("Cancel ride").assertDoesNotExist()
        composeRule.onNodeWithText("Back to home").performClick()
        assertEquals(1, done)
    }

    @Test
    fun anErrorIsShown() {
        show(state(Status.SEARCHING_DRIVER, error = "Could not reach the server."))

        composeRule.onNodeWithTag("track_error").assertExists()
    }
}
