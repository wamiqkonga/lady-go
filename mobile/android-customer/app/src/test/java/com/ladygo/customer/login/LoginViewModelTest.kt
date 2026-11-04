package com.ladygo.customer.login

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = mockk<AuthRepository>()
    private val viewModel = LoginViewModel(repository)

    @Test
    fun theButtonStaysOffUntilBothFieldsAreFilled() {
        assertFalse(viewModel.uiState.value.canSubmit)
        viewModel.onIdentifierChanged("asha@example.com")
        assertFalse(viewModel.uiState.value.canSubmit)
        viewModel.onPasswordChanged("Sunrise2026")
        assertTrue(viewModel.uiState.value.canSubmit)
    }

    @Test
    fun aSuccessfulLoginIsReported() =
        runTest {
            coEvery { repository.login("asha@example.com", "Sunrise2026") } returns AuthOutcome.Success
            viewModel.onIdentifierChanged(" asha@example.com ")
            viewModel.onPasswordChanged("Sunrise2026")

            viewModel.onLogin()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.loggedIn)
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun aFailureShowsTheMessageAndClearsWhenSheTypesAgain() =
        runTest {
            coEvery { repository.login(any(), any()) } returns AuthOutcome.Failed("Incorrect email, phone or password.")
            viewModel.onIdentifierChanged("asha@example.com")
            viewModel.onPasswordChanged("wrong")

            viewModel.onLogin()
            advanceUntilIdle()
            assertEquals("Incorrect email, phone or password.", viewModel.uiState.value.error)
            assertFalse(viewModel.uiState.value.loggedIn)

            viewModel.onPasswordChanged("Sunrise2026")
            assertNull(viewModel.uiState.value.error)
        }

    @Test
    fun nothingIsSentWithAnEmptyForm() =
        runTest {
            viewModel.onLogin()
            advanceUntilIdle()

            coVerify(exactly = 0) { repository.login(any(), any()) }
        }
}
