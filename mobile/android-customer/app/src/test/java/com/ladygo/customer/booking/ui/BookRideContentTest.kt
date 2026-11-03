package com.ladygo.customer.booking.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.ladygo.customer.api.generated.models.FareEstimateResponse
import com.ladygo.customer.booking.BookRideUiState
import com.ladygo.customer.booking.BookStep
import com.ladygo.customer.booking.GeoPoint
import com.ladygo.customer.booking.PickupState
import com.ladygo.customer.booking.PinState
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
        // The map sits above the results, so the list is below the first screenful.
        composeRule.onNodeWithText("Dal Gate, Srinagar").performScrollTo().performClick()

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
        composeRule.onNodeWithText("\u20B9185").assertExists()
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

    // --- map pin (docs/product/map-design.md section 3) ---

    @Test
    fun theMapSlotIsShownAboveTheSearchShortcutOnTheDestinationStep() {
        composeRule.setContent {
            BookRideContent(
                state = BookRideUiState(pickup = PickupState.Ready(pickup)),
                actions = BookRideActions(),
                map = { androidx.compose.material3.Text("fake map") },
            )
        }

        composeRule.onNodeWithTag("book_map").assertExists()
        composeRule.onNodeWithText("fake map").assertExists()
        // Search is a folded-away shortcut: closed until she asks for it.
        composeRule.onNodeWithTag("book_destination_field").assertDoesNotExist()
        composeRule.onNodeWithTag("book_open_search").performClick()
        composeRule.onNodeWithTag("book_destination_field").assertExists()
    }

    @Test
    fun confirmIsDisabledUntilAnAddressHasSettledThenRaisesTheCallback() {
        var confirmed = 0
        val pin = PinState.Resolved(dalGate, inServiceArea = true)
        var state by androidx.compose.runtime.mutableStateOf(BookRideUiState(pickup = PickupState.Ready(pickup), pin = PinState.Resolving))
        composeRule.setContent { BookRideContent(state, BookRideActions(onConfirmPin = { confirmed++ })) }
        composeRule.onNodeWithTag("book_confirm_pin").assertIsNotEnabled()

        composeRule.runOnIdle { state = state.copy(pin = pin) }
        composeRule.onNodeWithTag("book_pin_address").assertExists()
        composeRule.onNodeWithTag("book_confirm_pin").assertIsEnabled().performClick()

        assertEquals(1, confirmed)
    }

    @Test
    fun aPinOutsideTheServiceAreaSaysSoAndCannotBeConfirmed() {
        show(BookRideUiState(pickup = PickupState.Ready(pickup), pin = PinState.Resolved(dalGate, inServiceArea = false)))

        composeRule.onNodeWithTag("book_pin_out_of_area").assertExists()
        composeRule.onNodeWithTag("book_confirm_pin").assertIsNotEnabled()
    }

    @Test
    fun changingThePickupSwitchesTheConfirmLabelAndOffersToKeepTheCurrentOne() {
        var edit = 0
        var cancel = 0
        var state by androidx.compose.runtime.mutableStateOf(BookRideUiState(pickup = PickupState.Ready(pickup)))
        composeRule.setContent {
            BookRideContent(state, BookRideActions(onEditPickup = { edit++ }, onCancelEditPickup = { cancel++ }))
        }

        composeRule.onNodeWithTag("book_change_pickup").performClick()
        assertEquals(1, edit)

        composeRule.runOnIdle { state = state.copy(editingPickup = true) }
        composeRule.onNodeWithText("Confirm pickup").assertExists()
        composeRule.onNodeWithTag("book_change_pickup").assertDoesNotExist()
        composeRule.onNodeWithText("Keep current pickup").performClick()
        assertEquals(1, cancel)
    }

    // --- route on the fare screen ---

    @Test
    fun theFareStepShowsTheRouteMapSlotAboveTheFare() {
        composeRule.setContent {
            BookRideContent(
                state =
                    BookRideUiState(
                        step = BookStep.ESTIMATE,
                        pickup = PickupState.Ready(pickup),
                        destination = dalGate,
                        estimate = FareEstimateResponse(estimatedFare = 18500L, currency = "INR", routePolyline = "_p~iF~ps|U_ulLnnqC"),
                    ),
                actions = BookRideActions(),
                map = { androidx.compose.material3.Text("fake route map") },
            )
        }

        composeRule.onNodeWithTag("book_route_map").assertExists()
        composeRule.onNodeWithText("fake route map").assertExists()
        composeRule.onNodeWithTag("book_fare").assertExists()
    }
}
