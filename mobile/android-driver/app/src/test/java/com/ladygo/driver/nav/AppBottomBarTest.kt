package com.ladygo.driver.nav

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

class MarkerViewModel : ViewModel()

@RunWith(RobolectricTestRunner::class)
class AppBottomBarTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun theBarIsOnlyForTheFourTabPages() {
        listOf(Routes.HOME, Routes.EARNINGS, Routes.RIDES, Routes.ME).forEach { assertTrue(it, showsBottomBar(it)) }
        listOf(Routes.LOGIN, Routes.ONBOARDING_GATE, Routes.INCOMING_OFFER, Routes.ACTIVE_RIDE, Routes.RIDE_DETAIL, null)
            .forEach { assertFalse(it.toString(), showsBottomBar(it)) }
    }

    @Test
    fun offersAndRidesCanTakeOverFromAnyHomeTreeRoute() {
        listOf(Routes.HOME, Routes.EARNINGS, Routes.RIDES, Routes.ME, Routes.RIDE_DETAIL).forEach { assertTrue(it, isHomeTreeRoute(it)) }
        listOf(
            Routes.LOGIN,
            Routes.ONBOARDING_GATE,
            Routes.INCOMING_OFFER,
            Routes.ACTIVE_RIDE,
        ).forEach { assertFalse(it, isHomeTreeRoute(it)) }
    }

    @Test
    fun theBarIsHiddenOnTheOfferRoute() {
        composeRule.setContent { AppBottomBar(currentRoute = Routes.INCOMING_OFFER, onSelect = {}) }
        composeRule.onNodeWithTag(BOTTOM_BAR_TAG).assertDoesNotExist()
    }

    @Test
    fun theBarIsHiddenOnTheActiveRideRoute() {
        composeRule.setContent { AppBottomBar(currentRoute = Routes.ACTIVE_RIDE, onSelect = {}) }
        composeRule.onNodeWithTag(BOTTOM_BAR_TAG).assertDoesNotExist()
    }

    @Test
    fun theBarShowsFourLabelsOnAHomeTreeRoute() {
        var picked: MainTab? = null
        composeRule.setContent { AppBottomBar(currentRoute = Routes.EARNINGS, onSelect = { picked = it }) }

        listOf("Home", "Earnings", "Rides", "Me").forEach { composeRule.onNodeWithText(it).assertExists() }
        composeRule.onNodeWithText("Rides").performClick()
        assertTrue(picked == MainTab.RIDES)
    }

    private var nav: NavHostController? = null
    private var homeVm: MarkerViewModel? = null

    @Composable
    private fun Host() {
        val controller = rememberNavController()
        nav = controller
        val route =
            controller
                .currentBackStackEntryAsState()
                .value
                ?.destination
                ?.route
        androidx.compose.foundation.layout.Column {
            NavHost(controller, startDestination = Routes.HOME) {
                composable(Routes.HOME) {
                    homeVm = viewModel()
                    Text("home-page")
                }
                composable(Routes.EARNINGS) { Text("earnings-page") }
                composable(Routes.INCOMING_OFFER) { Text("offer-page") }
            }
            AppBottomBar(currentRoute = route, onSelect = controller::navigateToTab)
        }
    }

    @Test
    fun anOfferTakesTheScreenFromAnotherTabAndTheBarDisappears() {
        composeRule.setContent { Host() }
        composeRule.onNodeWithText("Earnings").performClick()
        composeRule.onNodeWithText("earnings-page").assertExists()
        composeRule.onNodeWithTag(BOTTOM_BAR_TAG).assertExists()

        composeRule.runOnUiThread { nav!!.navigate(Routes.INCOMING_OFFER) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("offer-page").assertExists()
        composeRule.onNodeWithTag(BOTTOM_BAR_TAG).assertDoesNotExist()

        composeRule.runOnUiThread { nav!!.popBackStack(Routes.HOME, inclusive = false) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("home-page").assertExists()
        composeRule.onNodeWithTag(BOTTOM_BAR_TAG).assertExists()
    }

    @Test
    fun switchingTabsKeepsTheHomeViewModel() {
        composeRule.setContent { Host() }
        val first = homeVm
        composeRule.onNodeWithText("Earnings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Home").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("home-page").assertExists()
        assertSame(first, homeVm)
    }

    @Test
    fun theRatingSheetShowsOnRestingScreensButNeverOverAnOfferOrAnActiveRide() {
        listOf(Routes.HOME, Routes.EARNINGS, Routes.RIDES, Routes.ME, Routes.HELP, Routes.RIDE_DETAIL)
            .forEach { assertTrue(it, isHomeTreeRoute(it)) }
        listOf(Routes.INCOMING_OFFER, Routes.ACTIVE_RIDE, Routes.LOGIN, Routes.ONBOARDING_GATE, null)
            .forEach { assertFalse(it.toString(), isHomeTreeRoute(it)) }
    }
}
