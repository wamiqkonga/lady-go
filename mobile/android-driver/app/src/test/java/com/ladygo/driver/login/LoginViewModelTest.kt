package com.ladygo.driver.login

import com.ladygo.driver.auth.AuthRepository
import com.ladygo.driver.auth.BlockedReason
import com.ladygo.driver.auth.PasswordLoginOutcome
import com.ladygo.driver.auth.RequestOtpOutcome
import com.ladygo.driver.auth.VerifyOtpOutcome
import com.ladygo.driver.testing.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LoginViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val authRepository = mockk<AuthRepository>()
    private val viewModel = LoginViewModel(authRepository)

    @Test
    fun startsOnPasswordEntryAndCanSwitchToACodeAndBack() {
        assertEquals(LoginStep.PASSWORD_ENTRY, viewModel.uiState.value.step)
        viewModel.onUseCodeInstead()
        assertEquals(LoginStep.PHONE_ENTRY, viewModel.uiState.value.step)
        viewModel.onUsePasswordInstead()
        assertEquals(LoginStep.PASSWORD_ENTRY, viewModel.uiState.value.step)
    }

    @Test
    fun aPasswordLoginSignsInAndClearsThePassword() =
        runTest {
            coEvery { authRepository.loginWithPassword("captain@example.com", "my little pink scooter") } returns
                PasswordLoginOutcome.LoggedIn
            viewModel.onIdentifierChanged("captain@example.com")
            viewModel.onPasswordChanged("my little pink scooter")

            viewModel.onPasswordLogin()
            testScheduler.advanceUntilIdle()

            assertTrue(viewModel.uiState.value.loggedIn)
            assertEquals("", viewModel.uiState.value.password)
        }

    @Test
    fun aFailedPasswordLoginShowsTheMessageAndClearsThePassword() =
        runTest {
            coEvery { authRepository.loginWithPassword(any(), any()) } returns
                PasswordLoginOutcome.Failed("Phone, email or password is not correct.")
            viewModel.onIdentifierChanged("captain@example.com")
            viewModel.onPasswordChanged("wrong-password-1")

            viewModel.onPasswordLogin()
            testScheduler.advanceUntilIdle()

            assertEquals("Phone, email or password is not correct.", viewModel.uiState.value.error)
            assertEquals("", viewModel.uiState.value.password)
            assertEquals(false, viewModel.uiState.value.loggedIn)
        }

    @Test
    fun aPasswordLoginWithABlankFieldIsANoOp() =
        runTest {
            viewModel.onIdentifierChanged("captain@example.com")

            viewModel.onPasswordLogin()
            testScheduler.advanceUntilIdle()

            coVerify(exactly = 0) { authRepository.loginWithPassword(any(), any()) }
        }

    @Test
    fun requestingAnOtpMovesToCodeEntryOnSuccess() =
        runTest {
            coEvery { authRepository.requestOtp("+14155550100") } returns RequestOtpOutcome.Sent
            viewModel.onPhoneChanged("+14155550100")

            viewModel.onRequestOtp()
            testScheduler.advanceUntilIdle()

            assertEquals(LoginStep.CODE_ENTRY, viewModel.uiState.value.step)
            assertNull(viewModel.uiState.value.error)
        }

    @Test
    fun requestingAnOtpSurfacesTheServerErrorAndStaysOnPhoneEntry() =
        runTest {
            coEvery { authRepository.requestOtp(any()) } returns RequestOtpOutcome.Failed("Too many requests.")
            viewModel.onUseCodeInstead()
            viewModel.onPhoneChanged("+14155550100")

            viewModel.onRequestOtp()
            testScheduler.advanceUntilIdle()

            assertEquals(LoginStep.PHONE_ENTRY, viewModel.uiState.value.step)
            assertEquals("Too many requests.", viewModel.uiState.value.error)
        }

    @Test
    fun requestingAnOtpWithABlankPhoneIsANoOp() =
        runTest {
            viewModel.onRequestOtp()
            testScheduler.advanceUntilIdle()

            coVerify(exactly = 0) { authRepository.requestOtp(any()) }
        }

    @Test
    fun verifyingAnOtpFlagsLoggedInOnSuccess() =
        runTest {
            coEvery { authRepository.verifyOtp("+14155550100", "123456") } returns VerifyOtpOutcome.LoggedIn
            viewModel.onPhoneChanged("+14155550100")
            viewModel.onCodeChanged("123456")

            viewModel.onVerifyOtp()
            testScheduler.advanceUntilIdle()

            assertTrue(viewModel.uiState.value.loggedIn)
        }

    @Test
    fun verifyingAWrongCodeSurfacesTheErrorAndDoesNotLogIn() =
        runTest {
            coEvery { authRepository.verifyOtp(any(), any()) } returns VerifyOtpOutcome.Failed("That code is wrong.")
            viewModel.onPhoneChanged("+14155550100")
            viewModel.onCodeChanged("000000")

            viewModel.onVerifyOtp()
            testScheduler.advanceUntilIdle()

            assertEquals("That code is wrong.", viewModel.uiState.value.error)
            assertEquals(false, viewModel.uiState.value.loggedIn)
        }

    @Test
    fun goingBackToPhoneEntryClearsTheCodeAndError() {
        viewModel.onCodeChanged("123456")

        viewModel.onBackToPhoneEntry()

        assertEquals(LoginStep.PHONE_ENTRY, viewModel.uiState.value.step)
        assertEquals("", viewModel.uiState.value.code)
    }

    @Test
    fun aDriverStillUnderReviewIsShownWhyAfterAPasswordSignIn() =
        runTest {
            coEvery { authRepository.loginWithPassword(any(), any()) } returns PasswordLoginOutcome.Blocked(BlockedReason.UNDER_REVIEW)
            viewModel.onIdentifierChanged("captain@example.com")
            viewModel.onPasswordChanged("my little pink scooter")

            viewModel.onPasswordLogin()
            testScheduler.advanceUntilIdle()

            assertEquals(BlockedReason.UNDER_REVIEW, viewModel.uiState.value.blocked)
            assertEquals(false, viewModel.uiState.value.loggedIn)
            assertEquals("", viewModel.uiState.value.password)
        }

    @Test
    fun aDriverStillUnderReviewIsShownWhyAfterACode() =
        runTest {
            coEvery { authRepository.verifyOtp(any(), any()) } returns VerifyOtpOutcome.Blocked(BlockedReason.REJECTED)
            viewModel.onUseCodeInstead()
            viewModel.onPhoneChanged("+919900000001")
            viewModel.onCodeChanged("123456")

            viewModel.onVerifyOtp()
            testScheduler.advanceUntilIdle()

            assertEquals(BlockedReason.REJECTED, viewModel.uiState.value.blocked)
            assertEquals(false, viewModel.uiState.value.loggedIn)
        }

    @Test
    fun goingBackFromTheBlockedScreenStartsAFreshSignIn() =
        runTest {
            coEvery { authRepository.loginWithPassword(any(), any()) } returns PasswordLoginOutcome.Blocked(BlockedReason.SUSPENDED)
            viewModel.onIdentifierChanged("captain@example.com")
            viewModel.onPasswordChanged("my little pink scooter")
            viewModel.onPasswordLogin()
            testScheduler.advanceUntilIdle()

            viewModel.onDismissBlocked()

            assertNull(viewModel.uiState.value.blocked)
            assertEquals(LoginStep.PASSWORD_ENTRY, viewModel.uiState.value.step)
        }
}
