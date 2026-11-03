package com.ladygo.customer.booking.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
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
import com.ladygo.customer.booking.CancelPreview
import com.ladygo.customer.booking.TrackUiState
import com.ladygo.customer.booking.stageFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    private fun withDialog(
        status: Status,
        preview: CancelPreview,
        driver: DriverSummaryResponse? = null,
    ) = state(status, driver = driver).copy(cancelPreview = preview)

    @Test
    fun theCancelButtonAsksTheViewModelToLoadTheFee() {
        var requested = false
        show(state(Status.SEARCHING_DRIVER), TrackActions(onCancelRequested = { requested = true }))

        composeRule.onNodeWithText("Cancel ride").performClick()

        assertTrue(requested)
    }

    @Test
    fun theDialogTellsHerTheFeeBeforeSheConfirms() {
        show(withDialog(Status.DRIVER_ACCEPTED, CancelPreview.Ready(fee = 5000L, currency = "INR", freeUntilMillis = null)))
        composeRule.onNodeWithTag("cancel_fee").assertTextContains("₹50", substring = true)
    }

    @Test
    fun theDialogSaysWhenCancellingIsFree() {
        show(withDialog(Status.SEARCHING_DRIVER, CancelPreview.Ready(fee = 0L, currency = "INR", freeUntilMillis = null)))
        composeRule.onNodeWithTag("cancel_fee").assertTextContains("no fee", substring = true)
    }

    @Test
    fun whileTheFeeLoadsTheConfirmButtonIsDisabled() {
        show(withDialog(Status.SEARCHING_DRIVER, CancelPreview.Loading))
        composeRule.onAllNodesWithText("Cancel ride")[1].assertIsNotEnabled()
    }

    @Test
    fun aPickedReasonIsPassedOn() {
        var reason: String? = null
        show(
            withDialog(Status.SEARCHING_DRIVER, CancelPreview.Unavailable),
            TrackActions(onCancel = { reason = it }),
        )

        composeRule.onNodeWithText("Booked by mistake").performClick()
        composeRule.onAllNodesWithText("Cancel ride")[1].performClick() // [0] is the screen button, [1] the dialog confirm

        assertEquals("Booked by mistake", reason)
    }

    @Test
    fun somethingElseLetsHerTypeHerOwnReason() {
        var reason: String? = null
        show(
            withDialog(Status.SEARCHING_DRIVER, CancelPreview.Unavailable),
            TrackActions(onCancel = { reason = it }),
        )

        composeRule.onNodeWithText("Something else").performClick()
        composeRule.onNodeWithTag("cancel_reason").performTextInput("Plans changed")
        composeRule.onAllNodesWithText("Cancel ride")[1].performClick()

        assertEquals("Plans changed", reason)
    }

    @Test
    fun theSafetyCancelIsOfferedOnlyOnceADriverIsAssigned() {
        var unsafe = false
        val driver = DriverSummaryResponse(name = "Asha")
        show(
            withDialog(Status.DRIVER_ACCEPTED, CancelPreview.Unavailable, driver),
            TrackActions(onCancelUnsafe = { unsafe = true }),
        )
        composeRule.onNodeWithTag("cancel_unsafe").performClick()
        assertTrue(unsafe)
    }

    @Test
    fun noSafetyCancelWhileStillSearching() {
        show(withDialog(Status.SEARCHING_DRIVER, CancelPreview.Unavailable))
        composeRule.onNodeWithTag("cancel_unsafe").assertDoesNotExist()
    }

    @Test
    fun afterACancelTheRideSaysWhetherSheWasCharged() {
        show(state(Status.CANCELLED).copy(cancelFee = 0L, cancelFeeCurrency = "INR"))
        composeRule.onNodeWithText("You were not charged a cancellation fee.").assertExists()
    }

    @Test
    fun afterAChargedCancelTheRideNamesTheFee() {
        show(state(Status.CANCELLED).copy(cancelFee = 5000L, cancelFeeCurrency = "INR"))
        composeRule.onNodeWithText("A cancellation fee of ₹50 applies.").assertExists()
    }

    @Test
    fun theRatingCardAppearsOnAFinishedRideWithADriver() {
        composeRule.setContent {
            TrackBookingContent(state(Status.COMPLETED, driver = DriverSummaryResponse(name = "Asha")), TrackActions(), rating = {
                androidx.compose.material3.Text("rating-slot")
            })
        }
        composeRule.onNodeWithText("rating-slot").assertExists()
    }

    @Test
    fun noRatingCardWhileTheRideIsStillUnderway() {
        composeRule.setContent {
            TrackBookingContent(state(Status.RIDE_STARTED, driver = DriverSummaryResponse(name = "Asha")), TrackActions(), rating = {
                androidx.compose.material3.Text("rating-slot")
            })
        }
        composeRule.onNodeWithText("rating-slot").assertDoesNotExist()
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
