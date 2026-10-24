package com.ladygo.driver.login

import com.ladygo.driver.auth.AuthRepository
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
    fun startsOnPhoneEntry() {
        assertEquals(LoginStep.PHONE_ENTRY, viewModel.uiState.value.step)
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
}
