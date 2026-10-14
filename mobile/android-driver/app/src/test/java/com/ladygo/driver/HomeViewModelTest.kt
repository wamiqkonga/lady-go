package com.ladygo.driver

import com.ladygo.driver.auth.AuthRepository
import com.ladygo.driver.testing.MainDispatcherRule
import com.ladygo.driver.ui.HomeViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HomeViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val authRepository = mockk<AuthRepository>()

    @Test
    fun exposesConfiguredApiBaseUrl() {
        val vm = HomeViewModel(authRepository)
        assertEquals(BuildConfig.API_BASE_URL, vm.uiState.value.apiBaseUrl)
    }

    @Test
    fun skeletonNoteIsPresent() {
        val vm = HomeViewModel(authRepository)
        assertTrue(
            vm.uiState.value.phaseNote
                .isNotBlank(),
        )
    }

    @Test
    fun onLogoutRevokesTheSession() =
        runTest {
            coEvery { authRepository.logout() } returns Unit
            val vm = HomeViewModel(authRepository)

            vm.onLogout()
            testScheduler.advanceUntilIdle()

            coVerify(exactly = 1) { authRepository.logout() }
        }
}
