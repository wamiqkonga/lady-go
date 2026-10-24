package com.ladygo.driver.earnings

import com.ladygo.driver.api.generated.models.DriverEarningsResponse
import com.ladygo.driver.api.generated.models.EarningsTotalsResponse
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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class EarningsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = mockk<EarningsRepository>()
    private var now = "2026-10-24T10:00:00Z"
    private val clock =
        object : Clock() {
            override fun getZone(): ZoneId = ZoneId.of("UTC")

            override fun withZone(zone: ZoneId): Clock = this

            override fun instant(): Instant = Instant.parse(now)
        }
    private val thisWeek = DateRange(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25))
    private val lastWeek = DateRange(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 18))

    private fun earnings(rides: Long) =
        DriverEarningsResponse(totals = EarningsTotalsResponse(rides = rides, gross = rides * 10_000, currency = "INR"))

    private fun viewModel() = EarningsViewModel(repository, clock)

    @Test
    fun startsLoadingThisWeekThenShowsTheNumbers() =
        runTest {
            coEvery { repository.fetch(thisWeek) } returns EarningsOutcome.Success(earnings(2))

            val vm = viewModel()
            assertTrue(vm.uiState.value.isLoading)
            assertEquals(thisWeek, vm.uiState.value.range)

            advanceUntilIdle()
            val state = vm.uiState.value
            assertFalse(state.isLoading)
            assertEquals(earnings(2), state.earnings)
            assertNull(state.error)
        }

    @Test
    fun aWeekWithNoRidesIsStillSuccessAndTheScreenDecidesItIsEmpty() =
        runTest {
            coEvery { repository.fetch(thisWeek) } returns EarningsOutcome.Success(earnings(0))

            val vm = viewModel()
            advanceUntilIdle()

            assertEquals(earnings(0), vm.uiState.value.earnings)
            assertNull(vm.uiState.value.error)
        }

    @Test
    fun aFailureShowsTheServersMessageAndRetryLoadsAgain() =
        runTest {
            coEvery { repository.fetch(thisWeek) } returns EarningsOutcome.Failed("Boom from the server")

            val vm = viewModel()
            advanceUntilIdle()
            assertEquals("Boom from the server", vm.uiState.value.error)
            assertNull(vm.uiState.value.earnings)
            assertFalse(vm.uiState.value.isLoading)

            coEvery { repository.fetch(thisWeek) } returns EarningsOutcome.Success(earnings(1))
            vm.onRetry()
            assertTrue(vm.uiState.value.isLoading)
            assertNull(vm.uiState.value.error)
            advanceUntilIdle()

            assertEquals(earnings(1), vm.uiState.value.earnings)
            assertNull(vm.uiState.value.error)
        }

    @Test
    fun choosingLastWeekRequestsLastWeeksDatesAndDropsTheOldNumbers() =
        runTest {
            coEvery { repository.fetch(thisWeek) } returns EarningsOutcome.Success(earnings(2))
            coEvery { repository.fetch(lastWeek) } returns EarningsOutcome.Success(earnings(5))
            val vm = viewModel()
            advanceUntilIdle()

            vm.onSelectWeek(WeekChoice.LAST_WEEK)
            assertEquals(WeekChoice.LAST_WEEK, vm.uiState.value.week)
            assertEquals(lastWeek, vm.uiState.value.range)
            assertNull(vm.uiState.value.earnings)
            assertTrue(vm.uiState.value.isLoading)
            advanceUntilIdle()

            assertEquals(earnings(5), vm.uiState.value.earnings)
            coVerify(exactly = 1) { repository.fetch(lastWeek) }
        }

    @Test
    fun selectingTheWeekAlreadyShownDoesNothing() =
        runTest {
            coEvery { repository.fetch(thisWeek) } returns EarningsOutcome.Success(earnings(2))
            val vm = viewModel()
            advanceUntilIdle()

            vm.onSelectWeek(WeekChoice.THIS_WEEK)
            advanceUntilIdle()

            coVerify(exactly = 1) { repository.fetch(thisWeek) }
        }

    @Test
    fun aSlowAnswerForTheOldWeekNeverOverwritesTheNewWeek() =
        runTest {
            val slow = CompletableDeferred<EarningsOutcome>()
            coEvery { repository.fetch(thisWeek) } coAnswers { slow.await() }
            coEvery { repository.fetch(lastWeek) } returns EarningsOutcome.Success(earnings(5))
            val vm = viewModel()
            advanceUntilIdle()

            vm.onSelectWeek(WeekChoice.LAST_WEEK)
            advanceUntilIdle()
            slow.complete(EarningsOutcome.Success(earnings(99)))
            advanceUntilIdle()

            assertEquals(earnings(5), vm.uiState.value.earnings)
        }

    @Test
    fun aFailedRefreshKeepsTheLastNumbersAndSaysSo() =
        runTest {
            coEvery { repository.fetch(thisWeek) } returns EarningsOutcome.Success(earnings(2))
            val vm = viewModel()
            advanceUntilIdle()

            coEvery { repository.fetch(thisWeek) } returns EarningsOutcome.Failed("offline")
            vm.onRefresh()
            assertTrue(vm.uiState.value.isRefreshing)
            assertEquals(earnings(2), vm.uiState.value.earnings)
            advanceUntilIdle()

            val state = vm.uiState.value
            assertFalse(state.isRefreshing)
            assertEquals(earnings(2), state.earnings)
            assertEquals("offline", state.refreshError)
            assertNull(state.error)
        }

    @Test
    fun aSuccessfulRefreshReplacesTheNumbersAndClearsTheBanner() =
        runTest {
            coEvery { repository.fetch(thisWeek) } returns EarningsOutcome.Success(earnings(2))
            val vm = viewModel()
            advanceUntilIdle()
            coEvery { repository.fetch(thisWeek) } returns EarningsOutcome.Failed("offline")
            vm.onRefresh()
            advanceUntilIdle()

            coEvery { repository.fetch(thisWeek) } returns EarningsOutcome.Success(earnings(3))
            vm.onRefresh()
            advanceUntilIdle()

            assertEquals(earnings(3), vm.uiState.value.earnings)
            assertNull(vm.uiState.value.refreshError)
        }

    @Test
    fun aRefreshAfterMidnightOnANewMondayMovesThisWeekForward() =
        runTest {
            coEvery { repository.fetch(thisWeek) } returns EarningsOutcome.Success(earnings(2))
            val nextWeek = DateRange(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 1))
            coEvery { repository.fetch(nextWeek) } returns EarningsOutcome.Success(earnings(0))
            val vm = viewModel()
            advanceUntilIdle()

            now = "2026-10-26T00:05:00Z"
            vm.onRefresh()
            assertEquals(nextWeek, vm.uiState.value.range)
            advanceUntilIdle()

            assertNotNull(vm.uiState.value.earnings)
            coVerify { repository.fetch(nextWeek) }
        }

    @Test
    fun comingBackToTheAppQuietlyRefreshesButNeverDuringALoad() =
        runTest {
            coEvery { repository.fetch(thisWeek) } returns EarningsOutcome.Success(earnings(2))
            val vm = viewModel()
            vm.onResume() // first load still running
            advanceUntilIdle()
            coVerify(exactly = 1) { repository.fetch(thisWeek) }

            vm.onResume()
            advanceUntilIdle()
            coVerify(exactly = 2) { repository.fetch(thisWeek) }
        }
}
