package com.ladygo.driver.account

import com.ladygo.driver.auth.AuthRepository
import com.ladygo.driver.testing.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MeViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = mockk<AccountRepository>()
    private val auth = mockk<AuthRepository>(relaxed = true)

    private val summary = RatingSummary(average = java.math.BigDecimal("4.5"), count = 2, distribution = null, tags = emptyMap())

    private fun overview(count: Int = 2) =
        AccountOverview(
            rating = Part.Loaded(summary.copy(count = count)),
            documents = Part.Loaded(emptyList()),
            vehicle = Part.Loaded(null),
        )

    private fun failed(message: String) = AccountOverview(Part.Failed(message), Part.Failed(message), Part.Failed(message))

    private fun viewModel() = MeViewModel(repository, auth)

    @Test
    fun startsLoadingThenShowsTheOverview() =
        runTest {
            coEvery { repository.fetchOverview() } returns overview()
            val vm = viewModel()
            assertTrue(vm.uiState.value.isLoading)

            advanceUntilIdle()

            assertFalse(vm.uiState.value.isLoading)
            assertEquals(overview(), vm.uiState.value.overview)
            assertNull(vm.uiState.value.error)
        }

    @Test
    fun everySectionFailingShowsTheErrorAndRetryRecovers() =
        runTest {
            coEvery { repository.fetchOverview() } returns failed("down")
            val vm = viewModel()
            advanceUntilIdle()
            assertEquals("down", vm.uiState.value.error)
            assertNull(vm.uiState.value.overview)

            coEvery { repository.fetchOverview() } returns overview()
            vm.onRetry()
            assertTrue(vm.uiState.value.isLoading)
            advanceUntilIdle()

            assertNull(vm.uiState.value.error)
            assertNotNull(vm.uiState.value.overview)
        }

    @Test
    fun onePartialFailureOnTheFirstLoadShowsTheOverviewWithTheFailedSectionAndNoBanner() =
        runTest {
            coEvery { repository.fetchOverview() } returns overview().copy(documents = Part.Failed("docs down"))
            val vm = viewModel()
            advanceUntilIdle()

            assertEquals(
                Part.Failed("docs down"),
                vm.uiState.value.overview
                    ?.documents,
            )
            assertNull(vm.uiState.value.refreshError)
            assertNull(vm.uiState.value.error)
        }

    @Test
    fun aFailedRefreshKeepsEverythingOnScreenWithABanner() =
        runTest {
            coEvery { repository.fetchOverview() } returns overview()
            val vm = viewModel()
            advanceUntilIdle()

            coEvery { repository.fetchOverview() } returns failed("offline")
            vm.onRefresh()
            assertTrue(vm.uiState.value.isRefreshing)
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isRefreshing)
            assertEquals(overview(), vm.uiState.value.overview)
            assertEquals("offline", vm.uiState.value.refreshError)
            assertNull(vm.uiState.value.error)
        }

    @Test
    fun aPartlyFailedRefreshKeepsTheOlderSectionAndSaysSo() =
        runTest {
            coEvery { repository.fetchOverview() } returns overview()
            val vm = viewModel()
            advanceUntilIdle()

            coEvery { repository.fetchOverview() } returns overview(count = 3).copy(rating = Part.Failed("rating down"))
            vm.onRefresh()
            advanceUntilIdle()

            val shown = vm.uiState.value.overview!!
            assertEquals(2, (shown.rating as Part.Loaded).value.count) // the earlier rating stays
            assertEquals("rating down", vm.uiState.value.refreshError)
        }

    @Test
    fun aSuccessfulRefreshClearsTheBanner() =
        runTest {
            coEvery { repository.fetchOverview() } returns overview()
            val vm = viewModel()
            advanceUntilIdle()
            coEvery { repository.fetchOverview() } returns failed("offline")
            vm.onRefresh()
            advanceUntilIdle()

            coEvery { repository.fetchOverview() } returns overview(count = 7)
            vm.onRefresh()
            advanceUntilIdle()

            assertNull(vm.uiState.value.refreshError)
            assertEquals(
                7,
                (
                    (
                        vm.uiState.value.overview!!
                            .rating
                    ) as Part.Loaded
                ).value.count,
            )
        }

    @Test
    fun comingBackToTheAppQuietlyRefreshesButNeverDuringALoad() =
        runTest {
            val gate = CompletableDeferred<AccountOverview>()
            coEvery { repository.fetchOverview() } coAnswers { gate.await() }
            val vm = viewModel()
            vm.onResume() // first load still running
            gate.complete(overview())
            advanceUntilIdle()
            coVerify(exactly = 1) { repository.fetchOverview() }

            coEvery { repository.fetchOverview() } returns overview()
            vm.onResume()
            advanceUntilIdle()
            coVerify(exactly = 2) { repository.fetchOverview() }
        }

    @Test
    fun logOutCallsTheSameLogoutAsHome() =
        runTest {
            coEvery { repository.fetchOverview() } returns overview()
            val vm = viewModel()

            vm.onLogout()
            advanceUntilIdle()

            coVerify(exactly = 1) { auth.logout() }
        }
}
