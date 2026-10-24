package com.ladygo.driver.history.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ladygo.driver.history.RideHistoryStatus
import com.ladygo.driver.history.rideItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RideDetailScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsTheRowReadOnlyAndBackWorks() {
        var backs = 0
        composeRule.setContent { RideDetailScreen(ride = rideItem(myRating = 5, ratedByRider = true), onBack = { backs++ }) }

        composeRule.onNodeWithText("Ride details").assertExists()
        composeRule.onNodeWithText("12 Lal Chowk").assertExists()
        composeRule.onNodeWithText("₹185").assertExists()
        composeRule.onNodeWithText("Cash").assertExists()
        composeRule.onNodeWithText("5 / 5").assertExists()
        composeRule.onNodeWithText("Rider has rated").assertExists()
        composeRule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }

    @Test
    fun aCancelledRideWithHiddenAddressesSaysSo() {
        composeRule.setContent {
            RideDetailScreen(ride = rideItem(status = RideHistoryStatus.CANCELLED, pickup = null, destination = null), onBack = {})
        }
        composeRule.onAllNodesWithText("Address hidden for this ride", useUnmergedTree = true).assertCountEquals(2)
    }

    @Test
    fun aMissingRideShowsANotAvailableMessage() {
        composeRule.setContent { RideDetailScreen(ride = null, onBack = {}) }
        composeRule.onNodeWithText("This ride is not available any more.").assertExists()
    }
}
