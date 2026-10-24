package com.ladygo.driver.nav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.ladygo.driver.auth.SessionManager
import com.ladygo.driver.history.RideHistoryOutcome
import com.ladygo.driver.history.RideHistoryRepository
import com.ladygo.driver.history.RideHistoryViewModel
import com.ladygo.driver.history.rideItem
import com.ladygo.driver.rating.RateRiderOutcome
import com.ladygo.driver.rating.RateRiderViewModel
import com.ladygo.driver.rating.RatingRepository
import com.ladygo.driver.rating.ui.RATE_SHEET_TAG
import com.ladygo.driver.rating.ui.RateRiderHost
import com.ladygo.driver.testing.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RatingWiringTest {
    @get:Rule(order = 0)
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private val ratings = mockk<RatingRepository>()
    private val loggedIn = MutableStateFlow(true)
    private val session = mockk<SessionManager> { every { isLoggedIn } returns loggedIn }
    private val ride = rideItem()
    private val historyRepository =
        mockk<RideHistoryRepository> { coEvery { fetchPage(0) } returns RideHistoryOutcome.Success(listOf(ride), hasMore = false) }

    @Test
    fun aRatingSavedFromTheDetailPageShowsOnItsHistoryRowWithoutAReload() {
        coEvery { ratings.rateRider(any(), any(), any()) } returns RateRiderOutcome.Success
        val rate = RateRiderViewModel(ratings, session)
        val history = RideHistoryViewModel(historyRepository)
        composeRule.setContent { RatedRowsEffect(rate, history) }
        composeRule.waitForIdle()

        rate.offer(ride.bookingId)
        rate.onScore(4)
        rate.onSubmit()
        composeRule.waitForIdle()

        assertEquals(4, history.item(ride.bookingId)?.myRating)
    }

    @Test
    fun anAlreadyRatedAnswerHidesTheRowsRateButtonWithoutAScore() {
        coEvery { ratings.rateRider(any(), any(), any()) } returns RateRiderOutcome.AlreadyRated
        val rate = RateRiderViewModel(ratings, session)
        val history = RideHistoryViewModel(historyRepository)
        composeRule.setContent { RatedRowsEffect(rate, history) }
        composeRule.waitForIdle()

        rate.offer(ride.bookingId)
        rate.onScore(2)
        rate.onSubmit()
        composeRule.waitForIdle()

        assertTrue(history.item(ride.bookingId)!!.alreadyRated)
        assertNull(history.item(ride.bookingId)!!.myRating)
    }

    @Test
    fun theSheetFollowsTheRouteAndIsGoneAfterLogout() {
        val rate = RateRiderViewModel(ratings, session)
        var visible by mutableStateOf(false)
        composeRule.setContent { RateRiderHost(viewModel = rate, visible = visible) }
        rate.offer(ride.bookingId)

        composeRule.onNodeWithTag(RATE_SHEET_TAG).assertDoesNotExist() // an offer / active ride owns the screen
        visible = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(RATE_SHEET_TAG).assertExists()

        loggedIn.value = false
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(RATE_SHEET_TAG).assertDoesNotExist()
        assertNull(rate.prompt.value)
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExist() = assertDoesNotExist()
