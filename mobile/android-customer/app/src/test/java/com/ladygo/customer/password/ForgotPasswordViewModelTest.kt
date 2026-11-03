package com.ladygo.customer.password

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
class ForgotPasswordViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = mockk<AuthRepository>()
    private val viewModel = ForgotPasswordViewModel(repository)

    @Test
    fun anInvalidEmailSendsNothing() =
        runTest {
            viewModel.onEmailChanged("nope")
            viewModel.onSendCode()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.emailError)
            coVerify(exactly = 0) { repository.requestPasswordReset(any()) }
        }

    @Test
    fun theCodeStepFollowsWhetherOrNotTheEmailHasAnAccount() =
        runTest {
            coEvery { repository.requestPasswordReset("asha@example.com") } returns AuthOutcome.Success
            viewModel.onEmailChanged("asha@example.com")

            viewModel.onSendCode()
            advanceUntilIdle()

            assertEquals(ForgotStep.NEW_PASSWORD, viewModel.uiState.value.step)
        }

    @Test
    fun theNewPasswordMustBeStrongAndConfirmed() {
        viewModel.onCodeChanged("123456")
        viewModel.onPasswordChanged("weak")
        viewModel.onConfirmPasswordChanged("weak")
        assertFalse(viewModel.uiState.value.canReset)
        viewModel.onPasswordChanged("Moonlight2027")
        assertFalse(viewModel.uiState.value.canReset)
        viewModel.onConfirmPasswordChanged("Moonlight2027")
        assertTrue(viewModel.uiState.value.canReset)
    }

    @Test
    fun aSuccessfulResetEndsOnTheDoneStep() =
        runTest {
            coEvery { repository.resetPassword("asha@example.com", "123456", "Moonlight2027") } returns AuthOutcome.Success
            viewModel.onEmailChanged("asha@example.com")
            viewModel.onCodeChanged("123456")
            viewModel.onPasswordChanged("Moonlight2027")
            viewModel.onConfirmPasswordChanged("Moonlight2027")

            viewModel.onResetPassword()
            advanceUntilIdle()

            assertEquals(ForgotStep.DONE, viewModel.uiState.value.step)
        }

    @Test
    fun aWrongCodeKeepsTheFormAndShowsTheMessage() =
        runTest {
            coEvery { repository.resetPassword(any(), any(), any()) } returns AuthOutcome.Failed("The code is invalid or has expired.")
            viewModel.onEmailChanged("asha@example.com")
            viewModel.onCodeChanged("000000")
            viewModel.onPasswordChanged("Moonlight2027")
            viewModel.onConfirmPasswordChanged("Moonlight2027")

            viewModel.onResetPassword()
            advanceUntilIdle()

            assertEquals("The code is invalid or has expired.", viewModel.uiState.value.error)
            assertFalse(viewModel.uiState.value.step == ForgotStep.DONE)
        }
}
