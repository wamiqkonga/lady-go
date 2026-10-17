package com.ladygo.customer.booking.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.ladygo.customer.api.generated.models.FareEstimateResponse
import com.ladygo.customer.booking.BookRideUiState
import com.ladygo.customer.booking.BookStep
import com.ladygo.customer.booking.GeoPoint
import com.ladygo.customer.booking.PickupState
import com.ladygo.customer.booking.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BookRideContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val pickup = Place("Lal Chowk, Srinagar", GeoPoint(34.08, 74.80))
    private val dalGate = Place("Dal Gate, Srinagar", GeoPoint(34.10, 74.83))

    private fun show(
        state: BookRideUiState,
        actions: BookRideActions = BookRideActions(),
    ) = composeRule.setContent { BookRideContent(state, actions) }

    @Test
    fun withoutPermissionOnlyTheLocationRationaleAndGrantButtonAreOffered() {
        var granted = 0
        show(BookRideUiState(pickup = PickupState.NeedsPermission), BookRideActions(onGrantLocation = { granted++ }))

        composeRule.onNodeWithText("Allow location").performClick()
        assertEquals(1, granted)
        composeRule.onNodeWithTag("book_destination_field").assertDoesNotExist()
    }

    @Test
    fun anUnavailableFixOffersRetry() {
        var retried = 0
        show(BookRideUiState(pickup = PickupState.Unavailable), BookRideActions(onRetryLocation = { retried++ }))

        composeRule.onNodeWithText("Try again").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun theDestinationStepShowsThePickupAndSearchIsDisabledForAShortQuery() {
        show(BookRideUiState(pickup = PickupState.Ready(pickup), query = "Da"))

        composeRule.onNodeWithTag("book_pickup_address").assertExists()
        composeRule.onNodeWithText("Search").assertIsNotEnabled()
    }

    @Test
    fun typingAndPickingACandidateRaisesTheCallbacks() {
        var typed: String? = null
        var picked: Place? = null
        show(
            BookRideUiState(pickup = PickupState.Ready(pickup), query = "Dal", candidates = listOf(dalGate), searched = true),
            BookRideActions(onQueryChanged = { typed = it }, onPickDestination = { picked = it }),
        )

        composeRule.onNodeWithTag("book_destination_field").performTextInput("x")
        composeRule.onNodeWithText("Dal Gate, Srinagar").performClick()

        assertTrue(typed.orEmpty().contains("x") && typed.orEmpty().contains("Dal"))
        assertEquals(dalGate, picked)
    }

    @Test
    fun noResultsIsSaidPlainly() {
        show(BookRideUiState(pickup = PickupState.Ready(pickup), query = "zzz", searched = true))

        composeRule.onNodeWithText("No places found. Try a more specific address.").assertExists()
    }

    @Test
    fun theEstimateStepShowsTheServersFareCashOnlyAndABookButton() {
        var booked = 0
        show(
            BookRideUiState(
                step = BookStep.ESTIMATE,
                pickup = PickupState.Ready(pickup),
                destination = dalGate,
                estimate = FareEstimateResponse(estimatedFare = 18500L, currency = "INR"),
            ),
            BookRideActions(onBook = { booked++ }),
        )

        composeRule.onNodeWithTag("book_fare").assertExists()
        composeRule.onNodeWithText("INR 18500").assertExists()
        composeRule.onNodeWithText("Pay your driver in cash.").assertExists()
        composeRule.onNodeWithText("Book ride").performClick()
        assertEquals(1, booked)
    }

    @Test
    fun bookIsDisabledWhileBusyAndAFareChangeMessageIsShown() {
        show(
            BookRideUiState(
                step = BookStep.ESTIMATE,
                pickup = PickupState.Ready(pickup),
                destination = dalGate,
                estimate = FareEstimateResponse(estimatedFare = 21000L, currency = "INR"),
                error = "The fare has changed since your estimate.",
                isBusy = true,
            ),
        )

        composeRule.onNodeWithText("Book ride").assertIsNotEnabled()
        composeRule.onNodeWithTag("book_error").assertExists()
    }
}
