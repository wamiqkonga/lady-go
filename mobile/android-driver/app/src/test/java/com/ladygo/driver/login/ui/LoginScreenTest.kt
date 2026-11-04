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

    @Test
    fun phoneEntryShowsSendCodeAndNotTheCodeField() {
        composeRule.setContent {
            LoginContent(
                state = LoginUiState(),
                onPhoneChanged = {},
                onCodeChanged = {},
                onRequestOtp = {},
                onVerifyOtp = {},
                onBackToPhoneEntry = {},
            )
        }

        composeRule.onNodeWithText("Send code").assertExists()
    }

    @Test
    fun sendCodeIsDisabledUntilAPhoneIsEntered() {
        var requested = false
        composeRule.setContent {
            LoginContent(
                state = LoginUiState(phone = ""),
                onPhoneChanged = {},
                onCodeChanged = {},
                onRequestOtp = { requested = true },
                onVerifyOtp = {},
                onBackToPhoneEntry = {},
            )
        }

        composeRule.onNodeWithText("Send code").performClick()

        assert(!requested) { "onRequestOtp must not fire for a blank phone" }
    }

    @Test
    fun typingAPhoneNumberInvokesTheCallback() {
        var typed: String? = null
        composeRule.setContent {
            LoginContent(
                state = LoginUiState(),
                onPhoneChanged = { typed = it },
                onCodeChanged = {},
                onRequestOtp = {},
                onVerifyOtp = {},
                onBackToPhoneEntry = {},
            )
        }

        composeRule.onNodeWithText("Phone number").performTextInput("+14155550100")

        assert(typed == "+14155550100")
    }

    @Test
    fun codeEntryShowsSignInAndTheEnteredPhoneNumber() {
        composeRule.setContent {
            LoginContent(
                state = LoginUiState(step = LoginStep.CODE_ENTRY, phone = "+14155550100"),
                onPhoneChanged = {},
                onCodeChanged = {},
                onRequestOtp = {},
                onVerifyOtp = {},
                onBackToPhoneEntry = {},
            )
        }

        composeRule.onNodeWithText("Sign in").assertExists()
        composeRule.onNodeWithText("Enter the code sent to +14155550100.").assertExists()
    }

    @Test
    fun anErrorMessageIsShownWhenPresent() {
        composeRule.setContent {
            LoginContent(
                state = LoginUiState(error = "That code is wrong."),
                onPhoneChanged = {},
                onCodeChanged = {},
                onRequestOtp = {},
                onVerifyOtp = {},
                onBackToPhoneEntry = {},
            )
        }

        composeRule.onNodeWithText("That code is wrong.").assertExists()
    }

    @Test
    fun passwordEntryShowsThePasswordFieldsAndTheForgotAndCodeLinks() {
        var forgot = false
        composeRule.setContent {
            LoginContent(
                state = LoginUiState(step = LoginStep.PASSWORD_ENTRY, identifier = "captain@example.com", password = "x"),
                onPhoneChanged = {},
                onCodeChanged = {},
                onRequestOtp = {},
                onVerifyOtp = {},
                onBackToPhoneEntry = {},
                onForgotPassword = { forgot = true },
            )
        }

        composeRule.onNodeWithText("Phone number or email").assertExists()
        composeRule.onNodeWithText("Password").assertExists()
        composeRule.onNodeWithText("Use a code instead").assertExists()
        composeRule.onNodeWithText("Forgot password?").performScrollTo().performClick()
        assertTrue(forgot)
    }

    @Test
    fun passwordSignInIsDisabledUntilBothFieldsAreFilled() {
        var signedIn = false
        composeRule.setContent {
            LoginContent(
                state = LoginUiState(step = LoginStep.PASSWORD_ENTRY, identifier = "captain@example.com", password = ""),
                onPhoneChanged = {},
                onCodeChanged = {},
                onRequestOtp = {},
                onVerifyOtp = {},
                onBackToPhoneEntry = {},
                onPasswordLogin = { signedIn = true },
            )
        }

        composeRule.onNodeWithText("Sign in").performClick()
        assertFalse(signedIn)
    }

    private fun showBlocked(
        reason: BlockedReason,
        onDismissBlocked: () -> Unit = {},
    ) = composeRule.setContent {
        LoginContent(
            state = LoginUiState(blocked = reason),
            onPhoneChanged = {},
            onCodeChanged = {},
            onRequestOtp = {},
            onVerifyOtp = {},
            onBackToPhoneEntry = {},
            onDismissBlocked = onDismissBlocked,
        )
    }

    @Test
    fun aDriverUnderReviewIsToldSoAndGivenTheSupportEmail() {
        showBlocked(BlockedReason.UNDER_REVIEW)

        composeRule.onNodeWithText("Your documents are under review").assertExists()
        composeRule.onNodeWithTag("blocked_support_email").assertExists()
        composeRule.onNodeWithText("supportladygo@gmail.com").assertExists()
        composeRule.onNodeWithText("Send code").assertDoesNotExist()
    }

    @Test
    fun aNotRegisteredDriverIsSentToTheWebsite() {
        showBlocked(BlockedReason.NOT_REGISTERED)
        composeRule.onNodeWithText("Finish registering on our website").assertExists()
    }

    @Test
    fun aRejectedOrSuspendedDriverIsToldToContactSupport() {
        showBlocked(BlockedReason.REJECTED)
        composeRule.onNodeWithText("Your application was not approved").assertExists()
        composeRule.onNodeWithText("supportladygo@gmail.com").assertExists()
    }

    @Test
    fun aSuspendedDriverIsToldToContactSupport() {
        showBlocked(BlockedReason.SUSPENDED)
        composeRule.onNodeWithText("Your account is suspended").assertExists()
    }

    @Test
    fun backToLoginLeavesTheBlockedScreen() {
        var dismissed = false
        showBlocked(BlockedReason.REJECTED) { dismissed = true }

        composeRule.onNodeWithText("Back to log in").performScrollTo().performClick()

        assertTrue(dismissed)
    }
}
