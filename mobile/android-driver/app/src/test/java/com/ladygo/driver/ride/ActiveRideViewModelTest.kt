package com.ladygo.driver.ride

import com.ladygo.driver.api.generated.models.BookingDetailResponse
import com.ladygo.driver.api.generated.models.BookingDetailResponse.PaymentMethod
import com.ladygo.driver.api.generated.models.BookingDetailResponse.Status
import com.ladygo.driver.auth.SessionManager
import com.ladygo.driver.location.AppPreferences
import com.ladygo.driver.realtime.RealtimeEvent
import com.ladygo.driver.realtime.RealtimeEvents
import com.ladygo.driver.testing.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
class ActiveRideViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val rideRepository = mockk<RideRepository>()
    private val selfieRepository = mockk<RiderSelfieRepository>()
    private val preferences =
        mockk<AppPreferences> {
            every { activeBookingId() } returns null
            every { setActiveBookingId(any()) } just runs
        }
    private val realtimeEvents = RealtimeEvents()
    private val loggedIn = MutableStateFlow(true)
    private val sessionManager = mockk<SessionManager> { every { isLoggedIn } returns loggedIn }
    private val id = UUID.randomUUID()

    private fun TestScope.viewModel(): ActiveRideViewModel {
        val vm = ActiveRideViewModel(rideRepository, selfieRepository, preferences, realtimeEvents, sessionManager)
        advanceUntilIdle()
        return vm
    }

    private fun booking(
        status: Status,
        payment: PaymentMethod = PaymentMethod.CASH,
    ) = BookingDetailResponse(id = id, status = status, paymentMethod = payment, pickupAddress = "A", destinationAddress = "B")

    @Test
    fun trackShowsTheToPickupStageAndRemembersTheBookingId() =
        runTest {
            val vm = viewModel()

            vm.track(booking(Status.DRIVER_ACCEPTED))

            assertEquals(RideStage.TO_PICKUP, vm.uiState.value.stage)
            assertTrue(vm.uiState.value.isActive)
            verify { preferences.setActiveBookingId(id.toString()) }
        }

    @Test
    fun imArrivedFromAcceptedCallsArrivingThenArrivedAndMovesOnlyOnTheServersAnswer() =
        runTest {
            coEvery { rideRepository.arriving(id) } returns RideActionOutcome.Success(booking(Status.DRIVER_ARRIVING))
            coEvery { rideRepository.arrived(id) } returns RideActionOutcome.Success(booking(Status.DRIVER_ARRIVED))
            coEvery { selfieRepository.load(id) } returns SelfieOutcome.Unavailable
            val vm = viewModel()
            vm.track(booking(Status.DRIVER_ACCEPTED))

            vm.onImArrived()
            assertTrue(vm.uiState.value.isActionInFlight)
            assertEquals(RideStage.TO_PICKUP, vm.uiState.value.stage) // the tap alone changes nothing
            advanceUntilIdle()

            coVerify(exactly = 1) { rideRepository.arriving(id) }
            coVerify(exactly = 1) { rideRepository.arrived(id) }
            assertEquals(RideStage.ARRIVED, vm.uiState.value.stage)
            assertFalse(vm.uiState.value.isActionInFlight)
        }

    @Test
    fun imArrivedFromArrivingSkipsTheArrivingCall() =
        runTest {
            coEvery { rideRepository.arrived(id) } returns RideActionOutcome.Success(booking(Status.DRIVER_ARRIVED))
            coEvery { selfieRepository.load(id) } returns SelfieOutcome.Unavailable
            val vm = viewModel()
            vm.track(booking(Status.DRIVER_ARRIVING))

            vm.onImArrived()
            advanceUntilIdle()

            coVerify(exactly = 0) { rideRepository.arriving(any()) }
            assertEquals(RideStage.ARRIVED, vm.uiState.value.stage)
        }

    @Test
    fun aRejectedArrivalShowsTheServersMessageAndThenReconcilesToTheRealState() =
        runTest {
            coEvery { rideRepository.arrived(id) } returns RideActionOutcome.Rejected("You are too far from the pickup point.")
            coEvery { rideRepository.fetch(id) } returns RideActionOutcome.Success(booking(Status.DRIVER_ARRIVING))
            val vm = viewModel()
            vm.track(booking(Status.DRIVER_ARRIVING))

            vm.onImArrived()
            advanceUntilIdle()

            assertEquals("You are too far from the pickup point.", vm.uiState.value.error)
            assertEquals(RideStage.TO_PICKUP, vm.uiState.value.stage)
            coVerify(exactly = 1) { rideRepository.fetch(id) }
        }

    @Test
    fun enteringArrivedLoadsTheSelfieOnce() =
        runTest {
            val image = SelfieImage(byteArrayOf(1, 2, 3))
            coEvery { selfieRepository.load(id) } returns SelfieOutcome.Loaded(image)
            val vm = viewModel()
            vm.track(booking(Status.DRIVER_ARRIVING))

            vm.track(booking(Status.DRIVER_ARRIVED))
            advanceUntilIdle()

            assertEquals(SelfieState.Loaded(image), vm.uiState.value.selfie)
            coVerify(exactly = 1) { selfieRepository.load(id) }
        }

    @Test
    fun leavingArrivedDropsTheSelfieFromMemory() =
        runTest {
            coEvery { selfieRepository.load(id) } returns SelfieOutcome.Loaded(SelfieImage(byteArrayOf(1)))
            coEvery { rideRepository.start(id, "1234") } returns RideActionOutcome.Success(booking(Status.RIDE_STARTED))
            val vm = viewModel()
            vm.track(booking(Status.DRIVER_ARRIVED))
            advanceUntilIdle()

            vm.onStartRide("1234")
            advanceUntilIdle()

            assertEquals(RideStage.IN_TRIP, vm.uiState.value.stage)
            assertEquals(SelfieState.Idle, vm.uiState.value.selfie)
        }

    @Test
    fun aBlankPinIsRejectedLocallyWithoutACall() =
        runTest {
            val vm = viewModel()
            vm.track(booking(Status.DRIVER_ARRIVED).copy())
            coEvery { selfieRepository.load(id) } returns SelfieOutcome.Unavailable

            vm.onStartRide("  ")
            advanceUntilIdle()

            assertEquals("Enter the ride PIN the rider reads out.", vm.uiState.value.error)
            coVerify(exactly = 0) { rideRepository.start(any(), any()) }
        }

    @Test
    fun aWrongPinShowsTheServersMessageAndStaysOnArrived() =
        runTest {
            coEvery { selfieRepository.load(id) } returns SelfieOutcome.Unavailable
            coEvery { rideRepository.start(id, "0000") } returns RideActionOutcome.Rejected("Incorrect ride PIN.")
            coEvery { rideRepository.fetch(id) } returns RideActionOutcome.Success(booking(Status.DRIVER_ARRIVED))
            val vm = viewModel()
            vm.track(booking(Status.DRIVER_ARRIVED))

            vm.onStartRide("0000")
            advanceUntilIdle()

            assertEquals("Incorrect ride PIN.", vm.uiState.value.error)
            assertEquals(RideStage.ARRIVED, vm.uiState.value.stage)
        }

    @Test
    fun aSecondTapWhileAnActionIsInFlightIsIgnored() =
        runTest {
            coEvery { rideRepository.complete(id) } returns RideActionOutcome.Success(booking(Status.PAYMENT_PENDING))
            val vm = viewModel()
            vm.track(booking(Status.RIDE_STARTED))

            vm.onComplete()
            vm.onComplete()
            advanceUntilIdle()

            coVerify(exactly = 1) { rideRepository.complete(id) }
        }

    @Test
    fun endEarlyRequiresAReasonAndSendsTheSafetyFlag() =
        runTest {
            coEvery { rideRepository.endEarly(id, "unwell", true) } returns RideActionOutcome.Success(booking(Status.PAYMENT_PENDING))
            val vm = viewModel()
            vm.track(booking(Status.RIDE_STARTED))

            vm.onEndEarly("   ", false)
            advanceUntilIdle()
            coVerify(exactly = 0) { rideRepository.endEarly(any(), any(), any()) }

            vm.onEndEarly(" unwell ", true)
            advanceUntilIdle()
            assertEquals(RideStage.PAYMENT, vm.uiState.value.stage)
        }

    @Test
    fun confirmingCashMarksItConfirmedOnlyOnSuccess() =
        runTest {
            coEvery { rideRepository.confirmCashCollected(id) } returns RideActionOutcome.Success(booking(Status.PAYMENT_PENDING))
            val vm = viewModel()
            vm.track(booking(Status.PAYMENT_PENDING))
            assertFalse(vm.uiState.value.cashConfirmed)

            vm.onConfirmCashCollected()
            advanceUntilIdle()

            assertTrue(vm.uiState.value.cashConfirmed)
            assertEquals(RideStage.PAYMENT, vm.uiState.value.stage)
        }

    @Test
    fun aNetworkFailureShowsAnErrorAndReconcilesInsteadOfAssumingAnOutcome() =
        runTest {
            coEvery { rideRepository.complete(id) } returns RideActionOutcome.NetworkFailure("offline")
            // The call actually landed server-side; the re-fetch is what reveals it.
            coEvery { rideRepository.fetch(id) } returns RideActionOutcome.Success(booking(Status.PAYMENT_PENDING))
            val vm = viewModel()
            vm.track(booking(Status.RIDE_STARTED))

            vm.onComplete()
            advanceUntilIdle()

            assertEquals("offline", vm.uiState.value.error)
            assertEquals(RideStage.PAYMENT, vm.uiState.value.stage)
        }

    @Test
    fun aPushedBookingUpdateForTheTrackedRideAppliesAndOneForAnotherRideIsIgnored() =
        runTest {
            val vm = viewModel()
            vm.track(booking(Status.DRIVER_ARRIVING))

            realtimeEvents.emit(RealtimeEvent.BookingUpdated(BookingDetailResponse(id = UUID.randomUUID(), status = Status.CANCELLED)))
            advanceUntilIdle()
            assertEquals(RideStage.TO_PICKUP, vm.uiState.value.stage)

            realtimeEvents.emit(RealtimeEvent.BookingUpdated(booking(Status.CANCELLED)))
            advanceUntilIdle()
            assertEquals(RideStage.ENDED, vm.uiState.value.stage)
        }

    @Test
    fun aTerminalStageForgetsTheRememberedBookingId() =
        runTest {
            val vm = viewModel()
            vm.track(booking(Status.RIDE_STARTED))

            realtimeEvents.emit(RealtimeEvent.BookingUpdated(booking(Status.COMPLETED)))
            advanceUntilIdle()

            assertEquals(RideStage.FINISHED, vm.uiState.value.stage)
            verify { preferences.setActiveBookingId(null) }
        }

    @Test
    fun reconcileAfterAnAppKillRestoresTheRideFromTheRememberedId() =
        runTest {
            every { preferences.activeBookingId() } returns id.toString()
            coEvery { rideRepository.fetch(id) } returns RideActionOutcome.Success(booking(Status.RIDE_STARTED))
            val vm = viewModel()
            assertNull(vm.uiState.value.booking)

            vm.reconcile()
            advanceUntilIdle()

            assertEquals(RideStage.IN_TRIP, vm.uiState.value.stage)
        }

    @Test
    fun reconcileDoesNothingWhenNoRideIsTracked() =
        runTest {
            val vm = viewModel()

            vm.reconcile()
            advanceUntilIdle()

            coVerify(exactly = 0) { rideRepository.fetch(any()) }
        }

    @Test
    fun aNotFoundOnReconcileShowsTheRideAsEndedInsteadOfSilentlyDroppingIt() =
        runTest {
            coEvery { rideRepository.fetch(id) } returns RideActionOutcome.Rejected("Not found", httpCode = 404)
            val vm = viewModel()
            vm.track(booking(Status.DRIVER_ARRIVING))

            vm.reconcile()
            advanceUntilIdle()

            assertEquals(RideStage.ENDED, vm.uiState.value.stage)
            assertTrue(vm.uiState.value.booking != null)
            verify { preferences.setActiveBookingId(null) }
        }

    @Test
    fun aNotFoundOnReconcileWithNothingOnScreenJustForgetsThePointer() =
        runTest {
            every { preferences.activeBookingId() } returns id.toString()
            coEvery { rideRepository.fetch(id) } returns RideActionOutcome.Rejected("Not found", httpCode = 404)
            val vm = viewModel()

            vm.reconcile()
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isActive)
            verify { preferences.setActiveBookingId(null) }
        }

    @Test
    fun anOtherThan403Or404RejectionOnReconcileKeepsTheRide() =
        runTest {
            coEvery { rideRepository.fetch(id) } returns RideActionOutcome.Rejected("Too many requests", httpCode = 429)
            val vm = viewModel()
            vm.track(booking(Status.RIDE_STARTED))

            vm.reconcile()
            advanceUntilIdle()

            assertEquals(RideStage.IN_TRIP, vm.uiState.value.stage)
            assertTrue(vm.uiState.value.isOngoing)
        }

    @Test
    fun aSlowStaleFetchNeverBouncesTheScreenBackwardsPastAnActionResult() =
        runTest {
            val slowFetch = CompletableDeferred<RideActionOutcome>()
            coEvery { rideRepository.fetch(id) } coAnswers { slowFetch.await() }
            coEvery { selfieRepository.load(id) } returns SelfieOutcome.Unavailable
            coEvery { rideRepository.start(id, "1234") } returns RideActionOutcome.Success(booking(Status.RIDE_STARTED))
            val vm = viewModel()
            vm.track(booking(Status.DRIVER_ARRIVED))
            advanceUntilIdle()

            vm.reconcile() // resume-triggered, still in flight
            vm.onStartRide("1234")
            advanceUntilIdle()
            assertEquals(RideStage.IN_TRIP, vm.uiState.value.stage)

            slowFetch.complete(RideActionOutcome.Success(booking(Status.DRIVER_ARRIVED))) // older snapshot lands last
            advanceUntilIdle()

            assertEquals(RideStage.IN_TRIP, vm.uiState.value.stage)
        }

    @Test
    fun aReconcileResultDoesNotReEnableButtonsWhileAnActionIsStillInFlight() =
        runTest {
            val slowAction = CompletableDeferred<RideActionOutcome>()
            coEvery { rideRepository.complete(id) } coAnswers { slowAction.await() }
            coEvery { rideRepository.fetch(id) } returns RideActionOutcome.Success(booking(Status.RIDE_STARTED))
            val vm = viewModel()
            vm.track(booking(Status.RIDE_STARTED))

            vm.onComplete()
            advanceUntilIdle()
            vm.reconcile()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.isActionInFlight)

            vm.onComplete() // a second tap must still be ignored
            slowAction.complete(RideActionOutcome.Success(booking(Status.PAYMENT_PENDING)))
            advanceUntilIdle()

            coVerify(exactly = 1) { rideRepository.complete(id) }
            assertFalse(vm.uiState.value.isActionInFlight)
            assertEquals(RideStage.PAYMENT, vm.uiState.value.stage)
        }

    @Test
    fun aFetchLandingAfterDoneDoesNotResurrectTheRide() =
        runTest {
            val slowFetch = CompletableDeferred<RideActionOutcome>()
            coEvery { rideRepository.fetch(id) } coAnswers { slowFetch.await() }
            val vm = viewModel()
            vm.track(booking(Status.RIDE_STARTED))

            vm.reconcile()
            vm.onDone()
            slowFetch.complete(RideActionOutcome.Success(booking(Status.COMPLETED)))
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isActive)
        }

    @Test
    fun anActionResultLandingAfterLogoutIsDropped() =
        runTest {
            val slowAction = CompletableDeferred<RideActionOutcome>()
            coEvery { rideRepository.complete(id) } coAnswers { slowAction.await() }
            val vm = viewModel()
            vm.track(booking(Status.RIDE_STARTED))

            vm.onComplete()
            advanceUntilIdle()
            loggedIn.value = false
            advanceUntilIdle()
            slowAction.complete(RideActionOutcome.Success(booking(Status.PAYMENT_PENDING)))
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isActive)
        }

    @Test
    fun aRepeatCashConfirmRejectedAsAlreadyConfirmedCountsAsConfirmed() =
        runTest {
            coEvery { rideRepository.confirmCashCollected(id) } returns
                RideActionOutcome.Rejected("Cash has already been confirmed for this ride.")
            coEvery { rideRepository.fetch(id) } returns RideActionOutcome.Success(booking(Status.PAYMENT_PENDING))
            val vm = viewModel()
            vm.track(booking(Status.PAYMENT_PENDING))

            vm.onConfirmCashCollected()
            advanceUntilIdle()

            assertTrue(vm.uiState.value.cashConfirmed)
        }

    @Test
    fun aSuccessfulArrivingIsShownEvenWhenArrivedIsRefusedByTheGate() =
        runTest {
            coEvery { rideRepository.arriving(id) } returns RideActionOutcome.Success(booking(Status.DRIVER_ARRIVING))
            coEvery { rideRepository.arrived(id) } returns RideActionOutcome.Rejected("You are too far from the pickup point.")
            coEvery { rideRepository.fetch(id) } returns RideActionOutcome.NetworkFailure("offline")
            val vm = viewModel()
            vm.track(booking(Status.DRIVER_ACCEPTED))

            vm.onImArrived()
            advanceUntilIdle()

            assertEquals(
                Status.DRIVER_ARRIVING,
                vm.uiState.value.booking
                    ?.status,
            )
            assertEquals("You are too far from the pickup point.", vm.uiState.value.error)
        }

    @Test
    fun aNetworkFailureDuringReconcileKeepsTheRideOnScreen() =
        runTest {
            coEvery { rideRepository.fetch(id) } returns RideActionOutcome.NetworkFailure("offline")
            val vm = viewModel()
            vm.track(booking(Status.RIDE_STARTED))

            vm.reconcile()
            advanceUntilIdle()

            assertEquals(RideStage.IN_TRIP, vm.uiState.value.stage)
        }

    @Test
    fun loggingOutClearsTheRide() =
        runTest {
            val vm = viewModel()
            vm.track(booking(Status.RIDE_STARTED))

            loggedIn.value = false
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isActive)
            verify { preferences.setActiveBookingId(null) }
        }

    @Test
    fun doneClearsTheRide() =
        runTest {
            val vm = viewModel()
            vm.track(booking(Status.COMPLETED))

            vm.onDone()

            assertFalse(vm.uiState.value.isActive)
        }
}
