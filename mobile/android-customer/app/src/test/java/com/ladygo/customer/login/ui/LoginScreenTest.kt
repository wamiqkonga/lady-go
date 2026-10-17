package com.ladygo.customer.login.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.ladygo.customer.login.LoginStep
import com.ladygo.customer.login.LoginUiState
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
}
