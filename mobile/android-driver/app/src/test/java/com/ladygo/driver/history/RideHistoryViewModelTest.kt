package com.ladygo.driver.history

import com.ladygo.driver.testing.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RideHistoryViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = mockk<RideHistoryRepository>()
    private val a = rideItem()
    private val b = rideItem()
    private val c = rideItem()

    private fun viewModel() = RideHistoryViewModel(repository)

    @Test
    fun loadsTheFirstPage() =
        runTest {
            coEvery { repository.fetchPage(0) } returns RideHistoryOutcome.Success(listOf(a, b), hasMore = true)
            val vm = viewModel()
            assertTrue(vm.uiState.value.isLoading)
            advanceUntilIdle()

            assertEquals(listOf(a, b), vm.uiState.value.items)
            assertFalse(vm.uiState.value.isLoading)
            assertTrue(vm.uiState.value.hasMore)
        }

    @Test
    fun noRidesIsAnEmptySuccess() =
        runTest {
            coEvery { repository.fetchPage(0) } returns RideHistoryOutcome.Success(emptyList(), hasMore = false)
            val vm = viewModel()
            advanceUntilIdle()

            assertTrue(
                vm.uiState.value.items
                    .isEmpty(),
            )
            assertNull(vm.uiState.value.error)
            assertFalse(vm.uiState.value.isLoading)
        }

    @Test
    fun aFirstPageFailureShowsTheMessageAndRetryRecovers() =
        runTest {
            coEvery { repository.fetchPage(0) } returns RideHistoryOutcome.Failed("down")
            val vm = viewModel()
            advanceUntilIdle()
            assertEquals("down", vm.uiState.value.error)

            coEvery { repository.fetchPage(0) } returns RideHistoryOutcome.Success(listOf(a), hasMore = false)
            vm.onRetry()
            assertTrue(vm.uiState.value.isLoading)
            advanceUntilIdle()

            assertEquals(listOf(a), vm.uiState.value.items)
            assertNull(vm.uiState.value.error)
        }

    @Test
    fun nextPagesAreAppendedWithoutDuplicates() =
        runTest {
            coEvery { repository.fetchPage(0) } returns RideHistoryOutcome.Success(listOf(a, b), hasMore = true)
            coEvery { repository.fetchPage(1) } returns RideHistoryOutcome.Success(listOf(b, c), hasMore = false)
            val vm = viewModel()
            advanceUntilIdle()

            vm.onLoadMore()
            assertTrue(vm.uiState.value.isLoadingMore)
            advanceUntilIdle()

            assertEquals(listOf(a, b, c), vm.uiState.value.items)
            assertFalse(vm.uiState.value.hasMore)
            vm.onLoadMore() // nothing left: ignored
            advanceUntilIdle()
            coVerify(exactly = 0) { repository.fetchPage(2) }
        }

    @Test
    fun onlyOneNextPageRequestIsInFlight() =
        runTest {
            coEvery { repository.fetchPage(0) } returns RideHistoryOutcome.Success(listOf(a), hasMore = true)
            coEvery { repository.fetchPage(1) } returns RideHistoryOutcome.Success(listOf(b), hasMore = true)
            val vm = viewModel()
            advanceUntilIdle()

            vm.onLoadMore()
            vm.onLoadMore()
            vm.onLoadMore()
            advanceUntilIdle()

            coVerify(exactly = 1) { repository.fetchPage(1) }
        }

    @Test
    fun aFailedNextPageKeepsTheRowsAndRetryLoadsThatSamePage() =
        runTest {
            coEvery { repository.fetchPage(0) } returns RideHistoryOutcome.Success(listOf(a), hasMore = true)
            coEvery { repository.fetchPage(1) } returns RideHistoryOutcome.Failed("flaky")
            val vm = viewModel()
            advanceUntilIdle()
            vm.onLoadMore()
            advanceUntilIdle()
            assertEquals("flaky", vm.uiState.value.loadMoreError)
            assertEquals(listOf(a), vm.uiState.value.items)

            coEvery { repository.fetchPage(1) } returns RideHistoryOutcome.Success(listOf(b), hasMore = false)
            vm.onLoadMore()
            advanceUntilIdle()

            assertEquals(listOf(a, b), vm.uiState.value.items)
            assertNull(vm.uiState.value.loadMoreError)
        }

    @Test
    fun refreshRestartsFromPageZeroAndReplacesTheRows() =
        runTest {
            coEvery { repository.fetchPage(0) } returns RideHistoryOutcome.Success(listOf(a, b), hasMore = true)
            coEvery { repository.fetchPage(1) } returns RideHistoryOutcome.Success(listOf(c), hasMore = false)
            val vm = viewModel()
            advanceUntilIdle()
            vm.onLoadMore()
            advanceUntilIdle()

            coEvery { repository.fetchPage(0) } returns RideHistoryOutcome.Success(listOf(c, a), hasMore = true)
            vm.onRefresh()
            assertTrue(vm.uiState.value.isRefreshing)
            advanceUntilIdle()

            assertEquals(listOf(c, a), vm.uiState.value.items)
            assertFalse(vm.uiState.value.isRefreshing)
            assertTrue(vm.uiState.value.hasMore)
        }

    @Test
    fun aFailedRefreshKeepsTheRowsAndShowsABanner() =
        runTest {
            coEvery { repository.fetchPage(0) } returns RideHistoryOutcome.Success(listOf(a), hasMore = false)
            val vm = viewModel()
            advanceUntilIdle()

            coEvery { repository.fetchPage(0) } returns RideHistoryOutcome.Failed("offline")
            vm.onRefresh()
            advanceUntilIdle()

            assertEquals(listOf(a), vm.uiState.value.items)
            assertEquals("offline", vm.uiState.value.refreshError)
            assertNull(vm.uiState.value.error)
            assertFalse(vm.uiState.value.isRefreshing)
        }

    @Test
    fun theDetailPageFindsItsRowInMemory() =
        runTest {
            coEvery { repository.fetchPage(0) } returns RideHistoryOutcome.Success(listOf(a), hasMore = false)
            val vm = viewModel()
            advanceUntilIdle()

            assertEquals(a, vm.item(a.bookingId))
            assertNull(vm.item(b.bookingId))
        }

    @Test
    fun comingBackToTheAppRefreshesWhenRowsAreShown() =
        runTest {
            coEvery { repository.fetchPage(0) } returns RideHistoryOutcome.Success(listOf(a), hasMore = false)
            val vm = viewModel()
            vm.onResume() // still loading: ignored
            advanceUntilIdle()
            coVerify(exactly = 1) { repository.fetchPage(0) }

            vm.onResume()
            advanceUntilIdle()
            coVerify(exactly = 2) { repository.fetchPage(0) }
        }

    @Test
    fun aSavedRatingShowsOnItsRowWithoutAReload() =
        runTest {
            coEvery { repository.fetchPage(0) } returns RideHistoryOutcome.Success(listOf(a, b), hasMore = false)
            val vm = viewModel()
            advanceUntilIdle()

            vm.onRated(b.bookingId, 4)

            assertNull(
                vm.uiState.value.items[0]
                    .myRating,
            )
            assertEquals(
                4,
                vm.uiState.value.items[1]
                    .myRating,
            )
            assertEquals(4, vm.item(b.bookingId)?.myRating)
            coVerify(exactly = 1) { repository.fetchPage(0) }
        }

    @Test
    fun ratingARideThatIsNotInTheListChangesNothing() =
        runTest {
            coEvery { repository.fetchPage(0) } returns RideHistoryOutcome.Success(listOf(a), hasMore = false)
            val vm = viewModel()
            advanceUntilIdle()

            vm.onRated(c.bookingId, 5)

            assertEquals(listOf(a), vm.uiState.value.items)
        }

    @Test
    fun comingBackToTheScreenKeepsRowsFromLaterPagesAndAddsNewOnes() =
        runTest {
            val newest = rideItem()
            coEvery { repository.fetchPage(0) } returns RideHistoryOutcome.Success(listOf(a, b), hasMore = true)
            coEvery { repository.fetchPage(1) } returns RideHistoryOutcome.Success(listOf(c), hasMore = false)
            val vm = viewModel()
            advanceUntilIdle()
            vm.onLoadMore()
            advanceUntilIdle()
            assertEquals(listOf(a, b, c), vm.uiState.value.items)

            // navigate away and back: page 0 now has a newer ride on top
            coEvery { repository.fetchPage(0) } returns RideHistoryOutcome.Success(listOf(newest, a), hasMore = true)
            vm.onResume()
            advanceUntilIdle()

            assertEquals(listOf(newest, a, b, c), vm.uiState.value.items)
            assertFalse(vm.uiState.value.hasMore) // page 2 was the last; the merge must not re-open paging
            assertFalse(vm.uiState.value.isRefreshing)
        }

    @Test
    fun aFailedQuietRefreshKeepsEveryRowAndShowsTheBanner() =
        runTest {
            coEvery { repository.fetchPage(0) } returns RideHistoryOutcome.Success(listOf(a), hasMore = true)
            coEvery { repository.fetchPage(1) } returns RideHistoryOutcome.Success(listOf(b), hasMore = false)
            val vm = viewModel()
            advanceUntilIdle()
            vm.onLoadMore()
            advanceUntilIdle()

            coEvery { repository.fetchPage(0) } returns RideHistoryOutcome.Failed("offline")
            vm.onResume()
            advanceUntilIdle()

            assertEquals(listOf(a, b), vm.uiState.value.items)
            assertEquals("offline", vm.uiState.value.refreshError)
        }

    @Test
    fun anAlreadyRatedAnswerHidesTheRateButtonWithoutInventingAScore() =
        runTest {
            coEvery { repository.fetchPage(0) } returns RideHistoryOutcome.Success(listOf(a), hasMore = false)
            val vm = viewModel()
            advanceUntilIdle()

            vm.onAlreadyRated(a.bookingId)

            val row = vm.item(a.bookingId)!!
            assertTrue(row.alreadyRated)
            assertNull(row.myRating)
            assertFalse(row.canRate(java.time.Instant.parse("2026-10-24T12:00:00Z")))
        }
}
