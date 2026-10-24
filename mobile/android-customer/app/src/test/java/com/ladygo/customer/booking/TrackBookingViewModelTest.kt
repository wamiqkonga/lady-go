package com.ladygo.customer.booking

import androidx.lifecycle.SavedStateHandle
import com.ladygo.customer.api.generated.models.BookingDetailResponse
import com.ladygo.customer.api.generated.models.BookingDetailResponse.Status
import com.ladygo.customer.testing.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
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
class TrackBookingViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = mockk<BookingRepository>()
    private val id = UUID.randomUUID()

    private fun booking(status: Status) = BookingDetailResponse(id = id, status = status)

    private fun viewModel() =
        TrackBookingViewModel(SavedStateHandle(mapOf(TrackBookingViewModel.BOOKING_ID_ARG to id.toString())), repository)

    private fun TestScope.refreshed(
        vm: TrackBookingViewModel,
        status: Status,
    ) {
        coEvery { repository.fetch(id) } returns BookingFetchOutcome.Found(booking(status))
        vm.refresh()
        advanceUntilIdle()
    }

    @Test
    fun everyServerStatusMapsToAStage() {
        val expected =
            mapOf(
                Status.REQUESTED to TrackStage.SEARCHING,
                Status.SEARCHING_DRIVER to TrackStage.SEARCHING,
                Status.DRIVER_ASSIGNED to TrackStage.DRIVER_COMING,
                Status.DRIVER_ACCEPTED to TrackStage.DRIVER_COMING,
                Status.DRIVER_ARRIVING to TrackStage.DRIVER_COMING,
                Status.DRIVER_ARRIVED to TrackStage.DRIVER_COMING,
                Status.RIDE_STARTED to TrackStage.IN_TRIP,
                Status.RIDE_COMPLETED to TrackStage.PAYMENT,
                Status.PAYMENT_PENDING to TrackStage.PAYMENT,
                Status.COMPLETED to TrackStage.FINISHED,
                Status.CANCELLED to TrackStage.CANCELLED,
            )
        assertEquals(Status.values().toSet(), expected.keys)
        expected.forEach { (status, stage) -> assertEquals(status.name, stage, stageFor(status)) }
        assertNull(stageFor(null))
    }

    @Test
    fun cancelAndEmergencyWindowsFollowTheStage() {
        assertTrue(TrackStage.SEARCHING.canCancel)
        assertTrue(TrackStage.DRIVER_COMING.canCancel)
        assertFalse(TrackStage.IN_TRIP.canCancel)
        assertTrue(TrackStage.IN_TRIP.showsEmergencyCall)
        assertFalse(TrackStage.SEARCHING.showsEmergencyCall)
        assertFalse(TrackStage.FINISHED.isLive)
    }

    @Test
    fun refreshShowsTheServersStatus() =
        runTest {
            val vm = viewModel()

            refreshed(vm, Status.DRIVER_ACCEPTED)

            assertEquals(TrackStage.DRIVER_COMING, vm.uiState.value.stage)
        }

    @Test
    fun aNotFoundMarksTheRideGoneSoTheScreenLeaves() =
        runTest {
            val vm = viewModel()
            coEvery { repository.fetch(id) } returns BookingFetchOutcome.NotFound

            vm.refresh()
            advanceUntilIdle()

            assertTrue(vm.uiState.value.gone)
        }

    @Test
    fun aNetworkFailureKeepsTheLastKnownStateAndShowsTheError() =
        runTest {
            val vm = viewModel()
            refreshed(vm, Status.SEARCHING_DRIVER)
            coEvery { repository.fetch(id) } returns BookingFetchOutcome.NetworkFailure("offline")

            vm.refresh()
            advanceUntilIdle()

            assertEquals(TrackStage.SEARCHING, vm.uiState.value.stage)
            assertEquals("offline", vm.uiState.value.error)
        }

    @Test
    fun cancelSendsTheReasonOrADefaultAndShowsTheCancelledRide() =
        runTest {
            val vm = viewModel()
            refreshed(vm, Status.SEARCHING_DRIVER)
            coEvery { repository.cancel(id, "Changed my mind") } returns CancelOutcome.Cancelled(booking(Status.CANCELLED))

            vm.onCancel("   ")
            advanceUntilIdle()

            assertEquals(TrackStage.CANCELLED, vm.uiState.value.stage)
        }

    @Test
    fun cancelIsIgnoredOnceTheTripHasStarted() =
        runTest {
            val vm = viewModel()
            refreshed(vm, Status.RIDE_STARTED)

            vm.onCancel("x")
            advanceUntilIdle()

            coVerify(exactly = 0) { repository.cancel(any(), any()) }
        }

    @Test
    fun aRejectedCancelShowsTheMessageThenReloadsTheRealState() =
        runTest {
            val vm = viewModel()
            refreshed(vm, Status.DRIVER_ARRIVED)
            coEvery { repository.cancel(id, any()) } returns CancelOutcome.Rejected("The ride has already started.")
            coEvery { repository.fetch(id) } returns BookingFetchOutcome.Found(booking(Status.RIDE_STARTED))

            vm.onCancel("x")
            advanceUntilIdle()

            assertEquals(TrackStage.IN_TRIP, vm.uiState.value.stage)
            assertFalse(vm.uiState.value.isCancelling)
        }

    @Test
    fun aSlowPollOvertakenByACancelDoesNotBringTheRideBack() =
        runTest {
            val vm = viewModel()
            refreshed(vm, Status.SEARCHING_DRIVER)
            val slowPoll = CompletableDeferred<BookingFetchOutcome>()
            coEvery { repository.fetch(id) } coAnswers { slowPoll.await() }
            coEvery { repository.cancel(id, any()) } returns CancelOutcome.Cancelled(booking(Status.CANCELLED))

            vm.refresh()
            vm.onCancel("x")
            advanceUntilIdle()
            slowPoll.complete(BookingFetchOutcome.Found(booking(Status.SEARCHING_DRIVER)))
            advanceUntilIdle()

            assertEquals(TrackStage.CANCELLED, vm.uiState.value.stage)
        }

    @Test
    fun aStaleEarlierStatusNeverReplacesAStartedTrip() =
        runTest {
            val vm = viewModel()
            refreshed(vm, Status.RIDE_STARTED)

            refreshed(vm, Status.DRIVER_ARRIVED)

            assertEquals(TrackStage.IN_TRIP, vm.uiState.value.stage)
        }

    @Test
    fun aDriverDroppingOffMayLegitimatelySendTheRideBackToSearching() =
        runTest {
            val vm = viewModel()
            refreshed(vm, Status.DRIVER_ACCEPTED)

            refreshed(vm, Status.SEARCHING_DRIVER)

            assertEquals(TrackStage.SEARCHING, vm.uiState.value.stage)
        }

    @Test
    fun aNewRefreshSupersedesASlowerOlderOne() =
        runTest {
            val vm = viewModel()
            val older = CompletableDeferred<BookingFetchOutcome>()
            coEvery { repository.fetch(id) } coAnswers { older.await() }
            vm.refresh()
            coEvery { repository.fetch(id) } returns BookingFetchOutcome.Found(booking(Status.DRIVER_ACCEPTED))
            vm.refresh()
            advanceUntilIdle()

            older.complete(BookingFetchOutcome.Found(booking(Status.SEARCHING_DRIVER)))
            advanceUntilIdle()

            assertEquals(TrackStage.DRIVER_COMING, vm.uiState.value.stage)
        }
}
