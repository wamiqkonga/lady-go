package com.ladygo.driver.onboarding

import com.ladygo.driver.api.generated.models.DriverProfileResponse.VerificationStatus
import com.ladygo.driver.auth.AuthRepository
import com.ladygo.driver.testing.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingGateViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = mockk<OnboardingRepository>()
    private val authRepository = mockk<AuthRepository>(relaxed = true)

    @Test
    fun loadsAndIsNotApprovedByDefaultStatuses() =
        runTest {
            coEvery { repository.fetchStatus() } returns OnboardingStatus.Known(VerificationStatus.PENDING_REVIEW)
            val vm = OnboardingGateViewModel(repository, authRepository)
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isApproved())
            assertFalse(vm.uiState.value.isLoading)
        }

    @Test
    fun isApprovedOnlyForAServerConfirmedApprovedStatus() =
        runTest {
            coEvery { repository.fetchStatus() } returns OnboardingStatus.Known(VerificationStatus.APPROVED)
            val vm = OnboardingGateViewModel(repository, authRepository)
            advanceUntilIdle()

            assertTrue(vm.uiState.value.isApproved())
        }

    @Test
    fun notStartedIsNotApproved() =
        runTest {
            coEvery { repository.fetchStatus() } returns OnboardingStatus.NotStarted
            val vm = OnboardingGateViewModel(repository, authRepository)
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isApproved())
        }

    @Test
    fun loadErrorIsNotApproved() =
        runTest {
            coEvery { repository.fetchStatus() } returns OnboardingStatus.LoadError("offline")
            val vm = OnboardingGateViewModel(repository, authRepository)
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isApproved())
        }

    @Test
    fun refreshReFetchesTheStatus() =
        runTest {
            coEvery { repository.fetchStatus() } returns OnboardingStatus.Known(VerificationStatus.PENDING_REVIEW)
            val vm = OnboardingGateViewModel(repository, authRepository)
            advanceUntilIdle()

            vm.refresh()
            advanceUntilIdle()

            coVerify(exactly = 2) { repository.fetchStatus() }
        }

    @Test
    fun refreshPicksUpAnApprovalThatHappenedWhileTheDriverWasAway() =
        runTest {
            coEvery { repository.fetchStatus() } returnsMany
                listOf(
                    OnboardingStatus.Known(VerificationStatus.PENDING_REVIEW),
                    OnboardingStatus.Known(VerificationStatus.APPROVED),
                )
            val vm = OnboardingGateViewModel(repository, authRepository)
            advanceUntilIdle()
            assertFalse(vm.uiState.value.isApproved())

            vm.refresh()
            advanceUntilIdle()

            assertTrue(vm.uiState.value.isApproved())
        }

    @Test
    fun logoutSignsTheDriverOut() =
        runTest {
            coEvery { repository.fetchStatus() } returns OnboardingStatus.Known(VerificationStatus.PENDING_REVIEW)
            val vm = OnboardingGateViewModel(repository, authRepository)
            advanceUntilIdle()

            vm.onLogout()
            advanceUntilIdle()

            coVerify(exactly = 1) { authRepository.logout() }
        }
}
