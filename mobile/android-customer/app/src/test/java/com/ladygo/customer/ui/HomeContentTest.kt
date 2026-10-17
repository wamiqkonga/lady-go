package com.ladygo.customer.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomeContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun logOutCallsBack() {
        var loggedOut = 0
        composeRule.setContent { HomeContent(HomeUiState(), {}, {}, onLogout = { loggedOut++ }) }

        composeRule.onNodeWithText("Log out").performClick()

        assertEquals(1, loggedOut)
    }

    @Test
    fun withNoRideInProgressItOffersBookingAndNotResume() {
        var booked = 0
        composeRule.setContent { HomeContent(HomeUiState(), { booked++ }, {}, {}) }

        composeRule.onNodeWithText("Book a ride").performClick()

        assertEquals(1, booked)
        composeRule.onNodeWithText("View my ride").assertDoesNotExist()
    }

    @Test
    fun aRideInProgressOffersResumeAndHidesBooking() {
        var resumed = 0
        composeRule.setContent { HomeContent(HomeUiState(activeBookingId = java.util.UUID.randomUUID()), {}, { resumed++ }, {}) }

        composeRule.onNodeWithText("View my ride").performClick()

        assertEquals(1, resumed)
        composeRule.onNodeWithText("Book a ride").assertDoesNotExist()
    }
}
