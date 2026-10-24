package com.ladygo.driver.rating

import com.ladygo.driver.auth.SessionManager
import com.ladygo.driver.testing.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class RateRiderViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = mockk<RatingRepository>()
    private val id = UUID.randomUUID()

    private val loggedIn = MutableStateFlow(true)
    private val sessionManager = mockk<SessionManager> { every { isLoggedIn } returns loggedIn }

    private fun viewModel() = RateRiderViewModel(repository, sessionManager)

    @Test
    fun nothingIsShownUntilARideIsOffered() {
        assertNull(viewModel().prompt.value)
    }

    @Test
    fun offeringOpensAnEmptyPromptAndSubmitNeedsAStar() =
        runTest {
            val vm = viewModel()
            vm.offer(id)

            assertEquals(RateRiderUiState(bookingId = id), vm.prompt.value)
            vm.onSubmit()
            advanceUntilIdle()

            coVerify(exactly = 0) { repository.rateRider(any(), any(), any()) }
        }

    @Test
    fun aSavedRatingThanksThenClosesItselfAndAnnouncesTheScore() =
        runTest {
            coEvery { repository.rateRider(id, 5, setOf(RiderRatingTag.RESPECTFUL)) } returns RateRiderOutcome.Success
            val vm = viewModel()
            val announced = mutableListOf<RiderRated>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.rated.toList(announced) }
            vm.offer(id)
            vm.onScore(5)
            vm.onToggleTag(RiderRatingTag.RESPECTFUL)

            vm.onSubmit()
            advanceTimeBy(100)

            assertEquals(RateResult.Thanks, vm.prompt.value?.result)
            assertEquals(listOf(RiderRated(id, 5)), announced)

            advanceUntilIdle()
            assertNull(vm.prompt.value)
        }

    @Test
    fun http409ShowsAlreadyRatedAndOffersOnlyClose() =
        runTest {
            coEvery { repository.rateRider(any(), any(), any()) } returns RateRiderOutcome.AlreadyRated
            val vm = viewModel()
            vm.offer(id)
            vm.onScore(4)

            vm.onSubmit()
            advanceUntilIdle()

            assertEquals(RateResult.AlreadyRated, vm.prompt.value?.result)
            vm.onScore(1) // a final answer cannot be edited
            vm.onSubmit()
            advanceUntilIdle()
            assertEquals(4, vm.prompt.value?.score)
            coVerify(exactly = 1) { repository.rateRider(any(), any(), any()) }
        }

    @Test
    fun anotherFourXxShowsTheServersMessage() =
        runTest {
            coEvery { repository.rateRider(any(), any(), any()) } returns
                RateRiderOutcome.Rejected("The rating window for this ride has closed.")
            val vm = viewModel()
            vm.offer(id)
            vm.onScore(2)

            vm.onSubmit()
            advanceUntilIdle()

            assertEquals(RateResult.Rejected("The rating window for this ride has closed."), vm.prompt.value?.result)
        }

    @Test
    fun aNetworkFailureKeepsTheChoicesAndRetryCanSucceed() =
        runTest {
            coEvery { repository.rateRider(any(), any(), any()) } returns RateRiderOutcome.NetworkFailure
            val vm = viewModel()
            vm.offer(id)
            vm.onScore(3)
            vm.onToggleTag(RiderRatingTag.ON_TIME)
            vm.onSubmit()
            advanceUntilIdle()

            val failed = vm.prompt.value!!
            assertEquals(RateResult.NetworkFailure, failed.result)
            assertFalse(failed.isSubmitting)
            assertEquals(3, failed.score)
            assertEquals(setOf(RiderRatingTag.ON_TIME), failed.tags)

            coEvery { repository.rateRider(any(), any(), any()) } returns RateRiderOutcome.Success
            vm.onSubmit()
            advanceTimeBy(100)

            assertEquals(RateResult.Thanks, vm.prompt.value?.result)
            coVerify(exactly = 2) { repository.rateRider(id, 3, setOf(RiderRatingTag.ON_TIME)) }
        }

    @Test
    fun skipClosesAtOnceWithoutCallingTheServer() =
        runTest {
            val vm = viewModel()
            vm.offer(id)
            vm.onScore(5)

            vm.onSkip()

            assertNull(vm.prompt.value)
            coVerify(exactly = 0) { repository.rateRider(any(), any(), any()) }
        }

    @Test
    fun aSecondTapWhileTheFirstIsInFlightIsIgnored() =
        runTest {
            val gate = CompletableDeferred<RateRiderOutcome>()
            coEvery { repository.rateRider(any(), any(), any()) } coAnswers { gate.await() }
            val vm = viewModel()
            vm.offer(id)
            vm.onScore(5)

            vm.onSubmit()
            vm.onSubmit()
            vm.onScore(1) // locked while saving
            advanceUntilIdle()
            assertTrue(vm.prompt.value!!.isSubmitting)
            assertEquals(5, vm.prompt.value!!.score)

            gate.complete(RateRiderOutcome.Success)
            advanceTimeBy(100)

            coVerify(exactly = 1) { repository.rateRider(any(), any(), any()) }
        }

    @Test
    fun skippingWhileSavingStillAnnouncesASavedRatingButNeverReopensThePrompt() =
        runTest {
            val gate = CompletableDeferred<RateRiderOutcome>()
            coEvery { repository.rateRider(any(), any(), any()) } coAnswers { gate.await() }
            val vm = viewModel()
            val announced = mutableListOf<RiderRated>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.rated.toList(announced) }
            vm.offer(id)
            vm.onScore(4)
            vm.onSubmit()

            vm.onSkip()
            gate.complete(RateRiderOutcome.Success)
            advanceUntilIdle()

            assertNull(vm.prompt.value)
            assertEquals(listOf(RiderRated(id, 4)), announced)
        }

    @Test
    fun aLateAnswerForAnEarlierRideNeverTouchesTheNewPrompt() =
        runTest {
            val gate = CompletableDeferred<RateRiderOutcome>()
            coEvery { repository.rateRider(any(), any(), any()) } coAnswers { gate.await() }
            val vm = viewModel()
            vm.offer(id)
            vm.onScore(4)
            vm.onSubmit()
            val other = UUID.randomUUID()
            vm.offer(other)

            gate.complete(RateRiderOutcome.NetworkFailure)
            advanceUntilIdle()

            assertEquals(RateRiderUiState(bookingId = other), vm.prompt.value)
        }

    @Test
    fun loggingOutClearsThePromptSoTheNextDriverNeverSeesIt() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            vm.offer(id)
            vm.onScore(5)

            loggedIn.value = false
            advanceUntilIdle()

            assertNull(vm.prompt.value)
            coVerify(exactly = 0) { repository.rateRider(any(), any(), any()) }
        }

    @Test
    fun offeringTheRideAlreadyBeingRatedKeepsTheChoices() =
        runTest {
            val vm = viewModel()
            vm.offer(id)
            vm.onScore(4)

            vm.offer(id) // e.g. the leftover-ride path after the normal Done path

            assertEquals(4, vm.prompt.value?.score)
            vm.offer(UUID.randomUUID())
            assertEquals(0, vm.prompt.value?.score)
        }

    @Test
    fun http409AlsoTellsTheRideListWithoutAScore() =
        runTest {
            coEvery { repository.rateRider(any(), any(), any()) } returns RateRiderOutcome.AlreadyRated
            val vm = viewModel()
            val told = mutableListOf<UUID>()
            val rated = mutableListOf<RiderRated>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.alreadyRated.toList(told) }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.rated.toList(rated) }
            vm.offer(id)
            vm.onScore(3)

            vm.onSubmit()
            advanceUntilIdle()

            assertEquals(listOf(id), told)
            assertTrue(rated.isEmpty())
        }
}
