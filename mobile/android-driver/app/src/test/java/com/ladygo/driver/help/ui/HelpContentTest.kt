package com.ladygo.driver.help.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.ladygo.driver.help.HelpUiState
import com.ladygo.driver.help.SupportContact
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HelpContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private class Taps {
        val calls = mutableListOf<String>()
        val whatsapps = mutableListOf<String>()
        var emergency = 0
        var copies = 0
        var retries = 0
        var backs = 0
    }

    private fun show(
        state: HelpUiState,
        copied: Boolean = false,
        taps: Taps = Taps(),
    ) = composeRule.setContent {
        HelpContent(
            state = state,
            copied = copied,
            onBack = { taps.backs++ },
            onRetry = { taps.retries++ },
            onCallSupport = { taps.calls += it },
            onWhatsApp = { taps.whatsapps += it },
            onCall112 = { taps.emergency++ },
            onCopyDiagnostics = { taps.copies++ },
        )
    }

    @Test
    fun bothNumbersShowBothButtonsWithTheRightValues() {
        val taps = Taps()
        show(HelpUiState(isLoading = false, contact = SupportContact("+911234567890", "911234567890")), taps = taps)

        composeRule.onNodeWithText("Call support").performClick()
        composeRule.onNodeWithText("Message on WhatsApp").performClick()

        assertEquals(listOf("+911234567890"), taps.calls)
        assertEquals(listOf("911234567890"), taps.whatsapps)
        composeRule.onNodeWithText("Support details coming soon").assertDoesNotExist()
    }

    @Test
    fun onlyAPhoneShowsOnlyCallSupport() {
        show(HelpUiState(isLoading = false, contact = SupportContact("+911234567890", null)))

        composeRule.onNodeWithText("Call support").assertExists()
        composeRule.onNodeWithText("Message on WhatsApp").assertDoesNotExist()
    }

    @Test
    fun onlyWhatsAppShowsOnlyThatButton() {
        show(HelpUiState(isLoading = false, contact = SupportContact(null, "911234567890")))

        composeRule.onNodeWithText("Call support").assertDoesNotExist()
        composeRule.onNodeWithText("Message on WhatsApp").assertExists()
    }

    @Test
    fun noNumbersSaysComingSoonButCall112AndDiagnosticsStay() {
        val taps = Taps()
        show(HelpUiState(isLoading = false, contact = SupportContact(null, null), driverReference = "a1b2c3d4"), taps = taps)

        composeRule.onNodeWithText("Support details coming soon").assertExists()
        composeRule.onNodeWithText("Call support").assertDoesNotExist()
        composeRule.onNodeWithText("Call 112").performScrollTo().performClick()
        composeRule.onNodeWithText("Copy diagnostics").performScrollTo().performClick()
        assertEquals(1, taps.emergency)
        assertEquals(1, taps.copies)
    }

    @Test
    fun aLoadFailureShowsTheMessageRetryAndStillOffersCall112() {
        val taps = Taps()
        show(HelpUiState(isLoading = false, error = "Server is down"), taps = taps)

        composeRule.onNodeWithText("Server is down").assertExists()
        composeRule.onNodeWithText("Retry").performClick()
        composeRule.onNodeWithText("Call 112").assertExists()
        assertEquals(1, taps.retries)
    }

    @Test
    fun loadingStillOffersCall112() {
        show(HelpUiState(isLoading = true))

        composeRule.onNodeWithText("Loading support details...").assertExists()
        composeRule.onNodeWithText("Call 112").assertExists()
    }

    @Test
    fun copyingShowsAConfirmationAndBackWorks() {
        val taps = Taps()
        show(HelpUiState(isLoading = false, contact = SupportContact(null, null), driverReference = "a1b2c3d4"), copied = true, taps = taps)

        composeRule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, taps.backs)
        composeRule.onNodeWithText("Diagnostics copied").performScrollTo().assertExists()
    }

    @Test
    fun copyIsDisabledUntilTheDriverReferenceIsKnown() {
        val taps = Taps()
        show(HelpUiState(isLoading = false, contact = SupportContact(null, null), driverReference = null), taps = taps)

        composeRule.onNodeWithText("Copy diagnostics").performScrollTo().assertIsNotEnabled()
    }
}
