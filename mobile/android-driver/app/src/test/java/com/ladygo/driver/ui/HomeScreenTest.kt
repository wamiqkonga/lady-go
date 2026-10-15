package com.ladygo.driver.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomeScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    // Each parameter below toggles one independent test scenario - bundling them into the
    // production dialog-state types here would just make the per-test overrides more awkward to
    // read, for no benefit this helper itself ever gets reused outside this one test file.
    @Suppress("LongParameterList")
    private fun setContent(
        state: HomeUiState = HomeUiState(),
        onToggleAvailability: () -> Unit = {},
        onLogout: () -> Unit = {},
        onDismissMessages: () -> Unit = {},
        showBatteryGuidanceDialog: Boolean = false,
        onBatteryGuidanceContinue: () -> Unit = {},
        onOpenBatteryOptimizationSettings: () -> Unit = {},
        showFineLocationDeniedExplainer: Boolean = false,
        onOpenAppSettings: () -> Unit = {},
        onDismissFineLocationExplainer: () -> Unit = {},
    ) {
        composeRule.setContent {
            HomeContent(
                state = state,
                onToggleAvailability = onToggleAvailability,
                onLogout = onLogout,
                onDismissMessages = onDismissMessages,
                batteryGuidanceDialog =
                    BatteryGuidanceDialogState(
                        show = showBatteryGuidanceDialog,
                        onContinue = onBatteryGuidanceContinue,
                        onOpenSettings = onOpenBatteryOptimizationSettings,
                    ),
                fineLocationDeniedDialog =
                    FineLocationDeniedDialogState(
                        show = showFineLocationDeniedExplainer,
                        onOpenSettings = onOpenAppSettings,
                        onDismiss = onDismissFineLocationExplainer,
                    ),
            )
        }
    }

    @Test
    fun offlineStateShowsGoOnline() {
        setContent(state = HomeUiState(availability = Availability.OFFLINE))

        composeRule.onNodeWithText("You're offline").assertExists()
        composeRule.onNodeWithText("Go online").assertExists()
    }

    @Test
    fun onlineStateShowsGoOffline() {
        setContent(state = HomeUiState(availability = Availability.ONLINE))

        composeRule.onNodeWithText("You're online").assertExists()
        composeRule.onNodeWithText("Go offline").assertExists()
    }

    @Test
    fun togglingStateDisablesTheButton() {
        var clicked = false
        setContent(
            state = HomeUiState(isTogglingAvailability = true),
            onToggleAvailability = { clicked = true },
        )

        composeRule.onNodeWithText("Please wait...").performClick()

        assert(!clicked) { "the toggle button must be disabled while a toggle is in flight" }
    }

    @Test
    fun clickingGoOnlineInvokesTheCallback() {
        var clicked = false
        setContent(
            state = HomeUiState(availability = Availability.OFFLINE),
            onToggleAvailability = { clicked = true },
        )

        composeRule.onNodeWithText("Go online").performClick()

        assert(clicked)
    }

    @Test
    fun anErrorMessageIsShownWhenPresent() {
        setContent(state = HomeUiState(error = "No approved vehicle on file."))

        composeRule.onNodeWithText("No approved vehicle on file.").assertExists()
    }

    @Test
    fun anInfoMessageIsShownWhenPresent() {
        setContent(state = HomeUiState(infoMessage = "You were set offline by Lady Go."))

        composeRule.onNodeWithText("You were set offline by Lady Go.").assertExists()
    }

    @Test
    fun tappingTheErrorMessageDismissesIt() {
        var dismissed = false
        setContent(state = HomeUiState(error = "oops"), onDismissMessages = { dismissed = true })

        composeRule.onNodeWithText("oops").performClick()

        assert(dismissed)
    }

    @Test
    fun batteryGuidanceDialogIsHiddenByDefault() {
        setContent(showBatteryGuidanceDialog = false)

        composeRule.onNodeWithText("Keep location sharing reliable").assertDoesNotExist()
    }

    @Test
    fun batteryGuidanceDialogContinueInvokesTheCallback() {
        var continued = false
        setContent(showBatteryGuidanceDialog = true, onBatteryGuidanceContinue = { continued = true })

        composeRule.onNodeWithText("Continue").performClick()

        assert(continued)
    }

    @Test
    fun batteryGuidanceDialogOpenSettingsInvokesTheCallback() {
        var opened = false
        setContent(showBatteryGuidanceDialog = true, onOpenBatteryOptimizationSettings = { opened = true })

        composeRule.onNodeWithText("Open settings").performClick()

        assert(opened)
    }

    @Test
    fun fineLocationDeniedExplainerIsHiddenByDefault() {
        setContent(showFineLocationDeniedExplainer = false)

        composeRule.onNodeWithText("Location permission needed").assertDoesNotExist()
    }

    @Test
    fun fineLocationDeniedExplainerShowsTheExactRationaleText() {
        setContent(showFineLocationDeniedExplainer = true)

        composeRule
            .onNodeWithText(
                "Lady Go uses your location while you are online to match you with nearby riders and to " +
                    "share your live position with your rider during a trip. Location sharing stops when you go offline.",
            ).assertExists()
    }

    @Test
    fun fineLocationDeniedExplainerNotNowDismissesWithoutOpeningSettings() {
        var dismissed = false
        var opened = false
        setContent(
            showFineLocationDeniedExplainer = true,
            onDismissFineLocationExplainer = { dismissed = true },
            onOpenAppSettings = { opened = true },
        )

        composeRule.onNodeWithText("Not now").performClick()

        assert(dismissed)
        assert(!opened)
    }
}
