package com.ladygo.customer.register

import com.ladygo.customer.auth.AuthOutcome
import com.ladygo.customer.auth.AuthRepository
import com.ladygo.customer.testing.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RegisterViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = mockk<AuthRepository>()
    private val viewModel = RegisterViewModel(repository)

    private fun fillDetails() {
        viewModel.onPhoneChanged("+91 98765 43210")
        viewModel.onEmailChanged(" asha@example.com ")
    }

    @Test
    fun bothPhoneAndEmailAreRequiredBeforeACodeCanBeSent() {
        assertFalse(viewModel.uiState.value.detailsValid)
        viewModel.onPhoneChanged("+919876543210")
        assertFalse(viewModel.uiState.value.detailsValid)
        viewModel.onEmailChanged("not-an-email")
        assertFalse(viewModel.uiState.value.detailsValid)
        assertTrue(viewModel.uiState.value.emailError)
        viewModel.onEmailChanged("asha@example.com")
        assertTrue(viewModel.uiState.value.detailsValid)
    }

    @Test
    fun aPhoneWithoutACountryCodeIsFlagged() {
        viewModel.onPhoneChanged("9876543210")
        assertTrue(viewModel.uiState.value.phoneError)
    }

    @Test
    fun sendingTheCodeMovesToTheCodeAndPasswordStep() =
        runTest {
            coEvery { repository.requestRegistrationOtp("+919876543210", "asha@example.com") } returns AuthOutcome.Success
            fillDetails()

            viewModel.onSendCode()
            advanceUntilIdle()

            assertEquals(RegisterStep.CODE_AND_PASSWORD, viewModel.uiState.value.step)
        }

    @Test
    fun anExistingAccountStaysOnTheFirstStepWithTheMessage() =
        runTest {
            coEvery { repository.requestRegistrationOtp(any(), any()) } returns AuthOutcome.Failed("Please log in instead.")
            fillDetails()

            viewModel.onSendCode()
            advanceUntilIdle()

            assertEquals(RegisterStep.DETAILS, viewModel.uiState.value.step)
            assertEquals("Please log in instead.", viewModel.uiState.value.error)
        }

    @Test
    fun theAccountNeedsACodeAMatchingStrongPassword() {
        viewModel.onCodeChanged("12ab34")
        assertEquals("1234", viewModel.uiState.value.code)
        viewModel.onCodeChanged("123456")
        viewModel.onPasswordChanged("weak")
        viewModel.onConfirmPasswordChanged("weak")
        assertFalse(viewModel.uiState.value.canCreate)
        assertTrue(viewModel.uiState.value.passwordError)
        viewModel.onPasswordChanged("Sunrise2026")
        assertFalse(viewModel.uiState.value.canCreate)
        assertTrue(viewModel.uiState.value.confirmError)
        viewModel.onConfirmPasswordChanged("Sunrise2026")
        assertTrue(viewModel.uiState.value.canCreate)
    }

    @Test
    fun creatingTheAccountSignsHerIn() =
        runTest {
            coEvery { repository.register("+919876543210", "asha@example.com", "123456", "Sunrise2026") } returns AuthOutcome.Success
            fillDetails()
            viewModel.onCodeChanged("123456")
            viewModel.onPasswordChanged("Sunrise2026")
            viewModel.onConfirmPasswordChanged("Sunrise2026")

            viewModel.onCreateAccount()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.registered)
        }

    @Test
    fun aWrongCodeShowsTheErrorAndKeepsTheForm() =
        runTest {
            coEvery { repository.register(any(), any(), any(), any()) } returns AuthOutcome.Failed("The code is invalid or has expired.")
            fillDetails()
            viewModel.onCodeChanged("000000")
            viewModel.onPasswordChanged("Sunrise2026")
            viewModel.onConfirmPasswordChanged("Sunrise2026")

            viewModel.onCreateAccount()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.registered)
            assertEquals("The code is invalid or has expired.", viewModel.uiState.value.error)
        }

    @Test
    fun nothingIsSentWhileTheFormIsInvalid() =
        runTest {
            viewModel.onSendCode()
            viewModel.onCreateAccount()
            advanceUntilIdle()

            coVerify(exactly = 0) { repository.requestRegistrationOtp(any(), any()) }
            coVerify(exactly = 0) { repository.register(any(), any(), any(), any()) }
        }
}
