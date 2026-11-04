package com.ladygo.customer.booking

import androidx.lifecycle.SavedStateHandle
import com.ladygo.customer.api.generated.models.SubmitRatingRequest
import com.ladygo.customer.testing.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class RateDriverViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val source = mockk<RatingSource>()
    private val id = UUID.randomUUID()
    private val tags = listOf(SubmitRatingRequest.Tags.POLITE)

    private fun viewModel() = RateDriverViewModel(SavedStateHandle(mapOf(TrackBookingViewModel.BOOKING_ID_ARG to id.toString())), source)

    @Test
    fun aSavedRatingIsDone() =
        runTest {
            coEvery { source.rateDriver(id, 5, tags, "lovely") } returns RatingOutcome.Submitted
            val vm = viewModel()

            vm.onSubmit(5, tags, "lovely")
            advanceUntilIdle()

            assertEquals(RatingStatus.DONE, vm.uiState.value.status)
        }

    @Test
    fun aRideAlreadyRatedDifferentlyIsAlsoDoneBecauseTheOldRatingIsFinal() =
        runTest {
            coEvery { source.rateDriver(id, 3, any(), any()) } returns RatingOutcome.AlreadyRated
            val vm = viewModel()

            vm.onSubmit(3, emptyList(), "")
            advanceUntilIdle()

            assertEquals(RatingStatus.DONE, vm.uiState.value.status)
        }

    @Test
    fun aFailureCanBeRetried() =
        runTest {
            coEvery { source.rateDriver(id, 4, any(), any()) } returns RatingOutcome.Failed
            val vm = viewModel()
            vm.onSubmit(4, emptyList(), "")
            advanceUntilIdle()
            assertEquals(RatingStatus.FAILED, vm.uiState.value.status)

            coEvery { source.rateDriver(id, 4, any(), any()) } returns RatingOutcome.Submitted
            vm.onSubmit(4, emptyList(), "")
            advanceUntilIdle()

            assertEquals(RatingStatus.DONE, vm.uiState.value.status)
        }

    @Test
    fun aScoreOutsideOneToFiveIsNeverSent() =
        runTest {
            val vm = viewModel()

            vm.onSubmit(0, emptyList(), "")
            vm.onSubmit(6, emptyList(), "")
            advanceUntilIdle()

            coVerify(exactly = 0) { source.rateDriver(any(), any(), any(), any()) }
            assertEquals(RatingStatus.IDLE, vm.uiState.value.status)
        }

    @Test
    fun aDoneRatingIsNotSentTwice() =
        runTest {
            coEvery { source.rateDriver(id, 5, any(), any()) } returns RatingOutcome.Submitted
            val vm = viewModel()
            vm.onSubmit(5, emptyList(), "")
            advanceUntilIdle()

            vm.onSubmit(5, emptyList(), "")
            advanceUntilIdle()

            coVerify(exactly = 1) { source.rateDriver(any(), any(), any(), any()) }
        }

    @Test
    fun aLongCommentIsTrimmedToTheServerLimit() =
        runTest {
            coEvery { source.rateDriver(id, 5, any(), any()) } returns RatingOutcome.Submitted
            val vm = viewModel()

            vm.onSubmit(5, emptyList(), "x".repeat(900))
            advanceUntilIdle()

            coVerify { source.rateDriver(id, 5, any(), "x".repeat(RateDriverViewModel.MAX_COMMENT_LENGTH)) }
        }
}
