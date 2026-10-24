package com.ladygo.customer.onboarding.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.ladygo.customer.onboarding.OnboardingState
import com.ladygo.customer.onboarding.OnboardingUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OnboardingContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun show(
        state: OnboardingUiState,
        onNameChanged: (String) -> Unit = {},
        onSaveName: () -> Unit = {},
        onAccept: () -> Unit = {},
        onRetry: () -> Unit = {},
        onLogout: () -> Unit = {},
    ) = composeRule.setContent {
        OnboardingContent(state, onNameChanged, onSaveName, onAccept, onRetry, onLogout)
    }

    @Test
    fun theNameStepDisablesContinueUntilANameIsEntered() {
        show(OnboardingUiState(isLoading = false, state = OnboardingState.NeedsName))

        composeRule.onNodeWithText("Continue").assertIsNotEnabled()
    }

    @Test
    fun typingANamePassesItUp() {
        var typed: String? = null
        show(OnboardingUiState(isLoading = false, state = OnboardingState.NeedsName), onNameChanged = { typed = it })

        composeRule.onNodeWithTag("onboarding_name_field").performTextInput("Aisha")

        assertEquals("Aisha", typed)
    }

    @Test
    fun theDeclarationStepShowsTheServersTextAndAcceptCallsBack() {
        var accepted = false
        show(
            OnboardingUiState(isLoading = false, state = OnboardingState.NeedsDeclaration("v1", "I am a woman travelling alone.")),
            onAccept = { accepted = true },
        )

        composeRule.onNodeWithText("I am a woman travelling alone.").assertExists()
        composeRule.onNodeWithText("I agree").performClick()

        assertEquals(true, accepted)
    }

    @Test
    fun theAcceptButtonIsDisabledWhileSubmitting() {
        show(OnboardingUiState(isLoading = false, state = OnboardingState.NeedsDeclaration("v1", "text"), isSubmitting = true))

        composeRule.onNodeWithText("I agree").assertIsNotEnabled()
    }

    @Test
    fun aLoadErrorShowsTheMessageAndRetry() {
        var retried = false
        show(OnboardingUiState(isLoading = false, state = OnboardingState.LoadError("Could not reach the server.")), onRetry = {
            retried =
                true
        })

        composeRule.onNodeWithText("Could not reach the server.").assertExists()
        composeRule.onNodeWithText("Try again").performClick()

        assertEquals(true, retried)
    }

    @Test
    fun aSubmitErrorIsShown() {
        show(OnboardingUiState(isLoading = false, state = OnboardingState.NeedsName, nameInput = "x", error = "Name is too long."))

        composeRule.onNodeWithTag("onboarding_error").assertExists()
    }

    @Test
    fun thereIsAlwaysALogOutWayOut() {
        var out = 0
        show(OnboardingUiState(isLoading = false, state = OnboardingState.LoadError("x")), onLogout = { out++ })

        composeRule.onNodeWithText("Log out").performClick()

        assertEquals(1, out)
    }
}
