package com.ladygo.customer.onboarding

import com.ladygo.customer.auth.AuthRepository
import com.ladygo.customer.testing.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = mockk<OnboardingRepository>()
    private val authRepository = mockk<AuthRepository>(relaxed = true)

    private fun TestScope.viewModel(initial: OnboardingState): OnboardingViewModel {
        coEvery { repository.fetchState() } returns initial
        val vm = OnboardingViewModel(repository, authRepository)
        advanceUntilIdle()
        return vm
    }

    @Test
    fun loadsTheStateTheServerReports() =
        runTest {
            val vm = viewModel(OnboardingState.NeedsName)

            assertFalse(vm.uiState.value.isLoading)
            assertEquals(OnboardingState.NeedsName, vm.uiState.value.state)
            assertFalse(vm.uiState.value.isReady)
        }

    @Test
    fun onlyAServerConfirmedReadyIsReady() =
        runTest {
            assertTrue(viewModel(OnboardingState.Ready).uiState.value.isReady)
            assertFalse(viewModel(OnboardingState.LoadError("x")).uiState.value.isReady)
        }

    @Test
    fun savingTheNameRefetchesSoTheServerDecidesWhatComesNext() =
        runTest {
            val vm = viewModel(OnboardingState.NeedsName)
            coEvery { repository.saveName("Aisha") } returns OnboardingStepOutcome.Done
            coEvery { repository.fetchState() } returns OnboardingState.NeedsDeclaration("v1", "text")

            vm.onNameChanged("  Aisha ")
            vm.onSaveName()
            advanceUntilIdle()

            assertEquals(OnboardingState.NeedsDeclaration("v1", "text"), vm.uiState.value.state)
            coVerify(exactly = 1) { repository.saveName("Aisha") } // trimmed
        }

    @Test
    fun aBlankNameIsNeverSent() =
        runTest {
            val vm = viewModel(OnboardingState.NeedsName)

            vm.onNameChanged("   ")
            vm.onSaveName()
            advanceUntilIdle()

            coVerify(exactly = 0) { repository.saveName(any()) }
        }

    @Test
    fun aRejectedNameShowsTheServersMessageAndStaysOnTheStep() =
        runTest {
            val vm = viewModel(OnboardingState.NeedsName)
            coEvery { repository.saveName(any()) } returns OnboardingStepOutcome.Rejected("Name is too long.")

            vm.onNameChanged("x")
            vm.onSaveName()
            advanceUntilIdle()

            assertEquals("Name is too long.", vm.uiState.value.error)
            assertEquals(OnboardingState.NeedsName, vm.uiState.value.state)
            assertFalse(vm.uiState.value.isSubmitting)
        }

    @Test
    fun acceptingSendsTheVersionShownAndThenBecomesReady() =
        runTest {
            val vm = viewModel(OnboardingState.NeedsDeclaration("v3", "text"))
            coEvery { repository.acceptDeclaration("v3") } returns OnboardingStepOutcome.Done
            coEvery { repository.fetchState() } returns OnboardingState.Ready

            vm.onAcceptDeclaration()
            advanceUntilIdle()

            assertTrue(vm.uiState.value.isReady)
        }

    @Test
    fun aFailedAcceptNeverMarksReady() =
        runTest {
            val vm = viewModel(OnboardingState.NeedsDeclaration("v3", "text"))
            coEvery { repository.acceptDeclaration("v3") } returns OnboardingStepOutcome.NetworkFailure("offline")

            vm.onAcceptDeclaration()
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isReady)
            assertEquals("offline", vm.uiState.value.error)
        }

    @Test
    fun acceptIsIgnoredWhenThereIsNoDeclarationToAccept() =
        runTest {
            val vm = viewModel(OnboardingState.NeedsName)

            vm.onAcceptDeclaration()
            advanceUntilIdle()

            coVerify(exactly = 0) { repository.acceptDeclaration(any()) }
        }

    @Test
    fun aRejectedAcceptReloadsTheDeclarationSoAStaleVersionIsNotResentForever() =
        runTest {
            val vm = viewModel(OnboardingState.NeedsDeclaration("v1", "old text"))
            coEvery { repository.acceptDeclaration("v1") } returns OnboardingStepOutcome.Rejected("Unknown declaration version.")
            coEvery { repository.fetchState() } returns OnboardingState.NeedsDeclaration("v2", "new text")

            vm.onAcceptDeclaration()
            advanceUntilIdle()

            assertEquals(OnboardingState.NeedsDeclaration("v2", "new text"), vm.uiState.value.state)
            assertEquals("Unknown declaration version.", vm.uiState.value.error)

            coEvery { repository.acceptDeclaration("v2") } returns OnboardingStepOutcome.Done
            coEvery { repository.fetchState() } returns OnboardingState.Ready
            vm.onAcceptDeclaration()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.isReady)
        }

    @Test
    fun logoutGoesThroughTheAuthRepository() =
        runTest {
            val vm = viewModel(OnboardingState.LoadError("x"))

            vm.onLogout()
            advanceUntilIdle()

            coVerify(exactly = 1) { authRepository.logout() }
        }
}
