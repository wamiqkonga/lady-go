package com.ladygo.customer.login.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.ladygo.customer.login.LoginUiState
import com.ladygo.customer.password.ForgotPasswordUiState
import com.ladygo.customer.password.ForgotStep
import com.ladygo.customer.password.ui.ForgotPasswordActions
import com.ladygo.customer.password.ui.ForgotPasswordContent
import com.ladygo.customer.register.RegisterStep
import com.ladygo.customer.register.RegisterUiState
import com.ladygo.customer.register.ui.RegisterActions
import com.ladygo.customer.register.ui.RegisterContent
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AuthScreensTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun login(
        state: LoginUiState = LoginUiState(),
        onRegister: () -> Unit = {},
        onForgot: () -> Unit = {},
    ) = composeRule.setContent {
        LoginContent(state, {}, {}, {}, onRegister, onForgot)
    }

    @Test
    fun loginOffersPasswordSignInForgotPasswordAndSignUp() {
        login()

        composeRule.onNodeWithTag("login_identifier").assertExists()
        composeRule.onNodeWithTag("login_password").assertExists()
        composeRule.onNodeWithText("Forgot password?").assertExists()
        composeRule.onNodeWithText("Create an account").assertExists()
        composeRule.onNodeWithText("Log in").assertIsNotEnabled()
    }

    @Test
    fun loginIsEnabledOnceBothFieldsAreFilled() {
        login(LoginUiState(identifier = "asha@example.com", password = "Sunrise2026"))
        composeRule.onNodeWithText("Log in").assertIsEnabled()
    }

    @Test
    fun theLinksGoToSignUpAndForgotPassword() {
        var register = false
        var forgot = false
        login(onRegister = { register = true }, onForgot = { forgot = true })

        composeRule.onNodeWithText("Create an account").performScrollTo().performClick()
        composeRule.onNodeWithText("Forgot password?").performScrollTo().performClick()

        assertTrue(register)
        assertTrue(forgot)
    }

    @Test
    fun aLoginErrorIsShown() {
        login(LoginUiState(error = "Incorrect email, phone or password."))
        composeRule.onNodeWithText("Incorrect email, phone or password.").assertExists()
    }

    @Test
    fun registrationAsksForBothPhoneAndEmailAndKeepsSendDisabledUntilValid() {
        composeRule.setContent { RegisterContent(RegisterUiState(), RegisterActions()) }

        composeRule.onNodeWithTag("register_phone").assertExists()
        composeRule.onNodeWithTag("register_email").assertExists()
        composeRule.onNodeWithText("Send code").assertIsNotEnabled()
    }

    @Test
    fun registrationSendIsEnabledForAValidPhoneAndEmail() {
        val state = RegisterUiState(phone = "+919876543210", email = "asha@example.com")
        composeRule.setContent { RegisterContent(state, RegisterActions()) }

        composeRule.onNodeWithText("Send code").assertIsEnabled()
    }

    @Test
    fun theSecondStepAsksForTheCodeAndAPasswordTwice() {
        val state = RegisterUiState(step = RegisterStep.CODE_AND_PASSWORD, email = "asha@example.com")
        composeRule.setContent { RegisterContent(state, RegisterActions()) }

        composeRule.onNodeWithTag("register_code").assertExists()
        composeRule.onNodeWithTag("register_password").assertExists()
        composeRule.onNodeWithTag("register_confirm").assertExists()
        composeRule.onNodeWithText("Create account").assertIsNotEnabled()
    }

    @Test
    fun forgotPasswordAsksForTheEmailThenTheCodeAndNewPassword() {
        composeRule.setContent { ForgotPasswordContent(ForgotPasswordUiState(), ForgotPasswordActions()) }
        composeRule.onNodeWithTag("forgot_email").assertExists()
        composeRule.onNodeWithText("Send code").assertIsNotEnabled()
    }

    @Test
    fun forgotPasswordSecondStepAndDoneStep() {
        val reset = ForgotPasswordUiState(step = ForgotStep.NEW_PASSWORD, email = "asha@example.com")
        composeRule.setContent { ForgotPasswordContent(reset, ForgotPasswordActions()) }
        composeRule.onNodeWithTag("forgot_code").assertExists()
        composeRule.onNodeWithTag("forgot_password").assertExists()
    }

    @Test
    fun theDoneStepOffersBackToLogin() {
        var back = false
        composeRule.setContent {
            ForgotPasswordContent(ForgotPasswordUiState(step = ForgotStep.DONE), ForgotPasswordActions(onBackToLogin = { back = true }))
        }

        composeRule.onNodeWithTag("forgot_done").assertExists()
        composeRule.onNodeWithText("Back to log in").performClick()
        assertTrue(back)
    }
}
