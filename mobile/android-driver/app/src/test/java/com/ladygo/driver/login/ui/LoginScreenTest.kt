package com.ladygo.driver.login.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.ladygo.driver.auth.BlockedReason
import com.ladygo.driver.login.LoginStep
import com.ladygo.driver.login.LoginUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LoginScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun show(
        state: LoginUiState,
        actions: LoginActions = LoginActions(),
    ) = composeRule.setContent { LoginContent(state, actions) }

    @Test
    fun phoneEntryAsksForPhoneAndEmailAndShowsSendCode() {
        show(LoginUiState())

        composeRule.onNodeWithTag("login_phone").assertExists()
        composeRule.onNodeWithTag("login_email").assertExists()
        composeRule.onNodeWithText("Send code").assertExists()
    }

    @Test
    fun sendCodeIsDisabledUntilBothPhoneAndEmailAreEntered() {
        var requested = false
        show(LoginUiState(phone = "+14155550100"), LoginActions(onRequestOtp = { requested = true }))

        composeRule.onNodeWithText("Send code").performScrollTo().performClick()

        assertFalse("onRequestOtp must not fire without an email", requested)
    }

    @Test
    fun sendCodeWorksOnceBothAreEntered() {
        var requested = false
        show(
            LoginUiState(phone = "+14155550100", email = "driver@example.com"),
            LoginActions(onRequestOtp = { requested = true }),
        )

        composeRule.onNodeWithText("Send code").performScrollTo().performClick()

        assertTrue(requested)
    }

    @Test
    fun typingAPhoneNumberInvokesTheCallback() {
        var typed: String? = null
        show(LoginUiState(), LoginActions(onPhoneChanged = { typed = it }))

        composeRule.onNodeWithText("Phone number").performTextInput("+14155550100")

        assertEquals("+14155550100", typed)
    }

    @Test
    fun codeEntryShowsSignInAndWhereTheCodeWasSent() {
        show(LoginUiState(step = LoginStep.CODE_ENTRY, phone = "+14155550100", email = "driver@example.com"))

        composeRule.onNodeWithText("Sign in").assertExists()
        composeRule.onNodeWithText("Enter the code we emailed to driver@example.com.").assertExists()
    }

    @Test
    fun anErrorMessageIsShownWhenPresent() {
        show(LoginUiState(error = "That code is wrong."))

        composeRule.onNodeWithText("That code is wrong.").assertExists()
    }

    @Test
    fun aDriverUnderReviewIsToldSoAndGivenTheSupportEmail() {
        show(LoginUiState(blocked = BlockedReason.UNDER_REVIEW))

        composeRule.onNodeWithText("Your documents are under review").assertExists()
        composeRule.onNodeWithTag("blocked_support_email").assertExists()
        composeRule.onNodeWithText("supportladygo@gmail.com").assertExists()
        composeRule.onNodeWithText("Send code").assertDoesNotExist()
    }

    @Test
    fun aNotRegisteredDriverIsSentToTheWebsite() {
        show(LoginUiState(blocked = BlockedReason.NOT_REGISTERED))
        composeRule.onNodeWithText("Finish registering on our website").assertExists()
    }

    @Test
    fun aRejectedDriverIsToldToContactSupport() {
        show(LoginUiState(blocked = BlockedReason.REJECTED))
        composeRule.onNodeWithText("Your application was not approved").assertExists()
        composeRule.onNodeWithText("supportladygo@gmail.com").assertExists()
    }

    @Test
    fun aSuspendedDriverIsToldToContactSupport() {
        show(LoginUiState(blocked = BlockedReason.SUSPENDED))
        composeRule.onNodeWithText("Your account is suspended").assertExists()
        composeRule.onNodeWithText("supportladygo@gmail.com").assertExists()
    }

    @Test
    fun backToLoginLeavesTheBlockedScreen() {
        var dismissed = false
        show(LoginUiState(blocked = BlockedReason.REJECTED), LoginActions(onDismissBlocked = { dismissed = true }))

        composeRule.onNodeWithText("Back to log in").performScrollTo().performClick()

        assertTrue(dismissed)
    }
}
