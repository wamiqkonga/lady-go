package com.ladygo.driver.offer

import com.ladygo.driver.api.generated.models.BookingDetailResponse
import com.ladygo.driver.api.generated.models.OfferResponse
import com.ladygo.driver.realtime.ServerClockSkew
import com.ladygo.driver.ride.RideActionOutcome
import com.ladygo.driver.ride.RideRepository
import com.ladygo.driver.testing.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class OfferViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val offerRepository = mockk<OfferRepository>()
    private val slotFlow = MutableStateFlow<OfferSlot>(OfferSlot.Empty)
    private val rideRepository = mockk<RideRepository>()
    private val offerCoordinator =
        mockk<OfferCoordinator>(relaxed = true) {
            every { slot } returns slotFlow
            // The real coordinator empties its slot when an offer is resolved.
            every { clearIfCurrent(any()) } answers { slotFlow.value = OfferSlot.Empty }
        }

    /** Advances once right after construction so the ViewModel's `init`-block collector has actually
     *  subscribed to [offerCoordinator]'s slot before a test mutates [slotFlow] - a `StateFlow`
     *  collector that hasn't started yet would miss an update made before it does. */
    private fun TestScope.viewModel(): OfferViewModel {
        val vm = OfferViewModel(offerRepository, offerCoordinator, rideRepository)
        advanceUntilIdle()
        return vm
    }

    private fun offer(
        id: UUID = UUID.randomUUID(),
        expiresAt: String = Instant.now().plusSeconds(60).toString(),
        bookingId: UUID? = null,
    ) = OfferResponse(offerId = id, bookingId = bookingId, estimatedFare = 200L, currency = "PKR", expiresAt = expiresAt)

    private fun show(offer: OfferResponse) {
        slotFlow.value = OfferSlot.Occupied(offer, ServerClockSkew.NONE)
    }

    @Test
    fun anOccupiedSlotShowsTheOffer() =
        runTest {
            val vm = viewModel()
            val theOffer = offer()

            show(theOffer)
            advanceUntilIdle()

            assertTrue(vm.uiState.value is OfferUiState.Showing)
            assertEquals(theOffer, (vm.uiState.value as OfferUiState.Showing).offer)
        }

    @Test
    fun reconcilePresentForwardsToTheCoordinator() =
        runTest {
            val theOffer = offer()
            coEvery { offerRepository.currentOffer() } returns CurrentOfferOutcome.Present(theOffer)
            val vm = viewModel()

            vm.reconcile(ServerClockSkew.NONE)
            advanceUntilIdle()

            verify(exactly = 1) { offerCoordinator.onReconciledOffer(theOffer, ServerClockSkew.NONE) }
        }

    @Test
    fun reconcileNoneForwardsToTheCoordinator() =
        runTest {
            coEvery { offerRepository.currentOffer() } returns CurrentOfferOutcome.None
            val vm = viewModel()

            vm.reconcile()
            advanceUntilIdle()

            verify(exactly = 1) { offerCoordinator.onReconciledNone() }
        }

    @Test
    fun acceptingResolvesToAcceptedAndClearsTheCoordinator() =
        runTest {
            val vm = viewModel()
            val theOffer = offer()
            show(theOffer)
            advanceUntilIdle()
            val booking = BookingDetailResponse(id = UUID.randomUUID(), status = BookingDetailResponse.Status.DRIVER_ACCEPTED)
            coEvery { offerRepository.accept(theOffer.offerId!!) } returns AcceptOfferOutcome.Accepted(booking)

            vm.onAccept()
            advanceUntilIdle()

            val resolved = vm.uiState.value as OfferUiState.Resolved
            assertEquals(booking, (resolved.outcome as OfferResolutionOutcome.Accepted).booking)
            verify(exactly = 1) { offerCoordinator.clearIfCurrent(theOffer.offerId!!) }
        }

    @Test
    fun aRejectedAcceptResolvesToNoLongerAvailable() =
        runTest {
            val vm = viewModel()
            val theOffer = offer()
            show(theOffer)
            advanceUntilIdle()
            coEvery { offerRepository.accept(theOffer.offerId!!) } returns AcceptOfferOutcome.Rejected("This offer has expired.")

            vm.onAccept()
            advanceUntilIdle()

            val resolved = vm.uiState.value as OfferUiState.Resolved
            assertEquals("This offer has expired.", (resolved.outcome as OfferResolutionOutcome.NoLongerAvailable).message)
        }

    @Test
    fun aLostAcceptResponseIsTrackedWhenTheBookingIsAlreadyAccepted() =
        runTest {
            val vm = viewModel()
            val bookingId = UUID.randomUUID()
            val theOffer = offer(bookingId = bookingId)
            show(theOffer)
            advanceUntilIdle()
            val booking = BookingDetailResponse(id = bookingId, status = BookingDetailResponse.Status.DRIVER_ACCEPTED)
            coEvery { offerRepository.accept(theOffer.offerId!!) } returns AcceptOfferOutcome.NetworkFailure("Could not reach the server.")
            coEvery { rideRepository.fetch(bookingId) } returns RideActionOutcome.Success(booking)

            vm.onAccept()
            advanceUntilIdle()

            val resolved = vm.uiState.value as OfferUiState.Resolved
            assertEquals(booking, (resolved.outcome as OfferResolutionOutcome.Accepted).booking)
        }

    @Test
    fun aRejectedAcceptThatActuallyLandedIsTrackedToo() =
        runTest {
            val vm = viewModel()
            val bookingId = UUID.randomUUID()
            val theOffer = offer(bookingId = bookingId)
            show(theOffer)
            advanceUntilIdle()
            val booking = BookingDetailResponse(id = bookingId, status = BookingDetailResponse.Status.DRIVER_ARRIVING)
            coEvery { offerRepository.accept(theOffer.offerId!!) } returns AcceptOfferOutcome.Rejected("This offer is no longer available.")
            coEvery { rideRepository.fetch(bookingId) } returns RideActionOutcome.Success(booking)

            vm.onAccept()
            advanceUntilIdle()

            val resolved = vm.uiState.value as OfferUiState.Resolved
            assertEquals(booking, (resolved.outcome as OfferResolutionOutcome.Accepted).booking)
        }

    @Test
    fun aRejectedAcceptStaysNoLongerAvailableWhenTheBookingIsNotTheDriversNow() =
        runTest {
            val vm = viewModel()
            val bookingId = UUID.randomUUID()
            val theOffer = offer(bookingId = bookingId)
            show(theOffer)
            advanceUntilIdle()
            coEvery { offerRepository.accept(theOffer.offerId!!) } returns AcceptOfferOutcome.Rejected("This offer is no longer available.")
            coEvery { rideRepository.fetch(bookingId) } returns
                RideActionOutcome.Success(BookingDetailResponse(id = bookingId, status = BookingDetailResponse.Status.DRIVER_ASSIGNED))

            vm.onAccept()
            advanceUntilIdle()

            val resolved = vm.uiState.value as OfferUiState.Resolved
            assertEquals("This offer is no longer available.", (resolved.outcome as OfferResolutionOutcome.NoLongerAvailable).message)
        }

    @Test
    fun consumingAResolutionShowsAnOfferThatArrivedMeanwhile() =
        runTest {
            val vm = viewModel()
            val first = offer()
            show(first)
            advanceUntilIdle()
            coEvery { offerRepository.decline(first.offerId!!) } returns DeclineOfferOutcome.Declined
            vm.onDecline()
            advanceUntilIdle()
            val second = offer()
            show(second) // arrives while the Resolved state is up: the slot collector skips it

            vm.onResolutionConsumed()

            assertEquals(second, (vm.uiState.value as OfferUiState.Showing).offer)
        }

    @Test
    fun consumingAResolutionWithAnEmptySlotShowsNothing() =
        runTest {
            val vm = viewModel()

            vm.onResolutionConsumed()

            assertEquals(OfferUiState.None, vm.uiState.value)
        }

    @Test
    fun aNetworkFailureOnAcceptStaysShowingAndIsRetryableWhenNotExpired() =
        runTest {
            val vm = viewModel()
            val theOffer = offer(expiresAt = Instant.now().plusSeconds(60).toString())
            show(theOffer)
            advanceUntilIdle()
            coEvery { offerRepository.accept(theOffer.offerId!!) } returns AcceptOfferOutcome.NetworkFailure("Could not reach the server.")

            vm.onAccept()
            advanceUntilIdle()

            val showing = vm.uiState.value as OfferUiState.Showing
            assertEquals("Could not reach the server.", showing.error)
            assertEquals(false, showing.isActionInFlight)
        }

    /** The exact race the fresh-context review flagged: the local countdown already hit zero *while*
     *  the accept call was in flight (so `onLocalCountdownExpired` was a no-op then), and now the
     *  call comes back as a network failure - without re-checking expiry here, the screen would be
     *  stuck at 0s with both buttons permanently disabled. */
    @Test
    fun aNetworkFailureOnAcceptResolvesToExpiredWhenTheOfferHasSinceExpired() =
        runTest {
            val vm = viewModel()
            val theOffer = offer(expiresAt = Instant.now().minusSeconds(5).toString())
            show(theOffer)
            advanceUntilIdle()
            coEvery { offerRepository.accept(theOffer.offerId!!) } returns AcceptOfferOutcome.NetworkFailure("Could not reach the server.")

            vm.onAccept()
            advanceUntilIdle()

            val resolved = vm.uiState.value as OfferUiState.Resolved
            assertEquals(OfferResolutionOutcome.Expired, resolved.outcome)
        }

    @Test
    fun aDoubleTapOnAcceptDoesNotFireASecondCall() =
        runTest {
            val vm = viewModel()
            val theOffer = offer()
            show(theOffer)
            advanceUntilIdle()
            coEvery { offerRepository.accept(theOffer.offerId!!) } coAnswers {
                delay(1_000)
                AcceptOfferOutcome.Accepted(BookingDetailResponse())
            }

            vm.onAccept()
            vm.onAccept()
            advanceUntilIdle()

            coVerify(exactly = 1) { offerRepository.accept(theOffer.offerId!!) }
        }

    @Test
    fun decliningResolvesToDeclinedAndClearsTheCoordinator() =
        runTest {
            val vm = viewModel()
            val theOffer = offer()
            show(theOffer)
            advanceUntilIdle()
            coEvery { offerRepository.decline(theOffer.offerId!!) } returns DeclineOfferOutcome.Declined

            vm.onDecline()
            advanceUntilIdle()

            val resolved = vm.uiState.value as OfferUiState.Resolved
            assertEquals(OfferResolutionOutcome.Declined, resolved.outcome)
            verify(exactly = 1) { offerCoordinator.clearIfCurrent(theOffer.offerId!!) }
        }

    @Test
    fun localCountdownExpiryResolvesToExpiredWhenNothingIsInFlight() =
        runTest {
            val vm = viewModel()
            val theOffer = offer()
            show(theOffer)
            advanceUntilIdle()

            vm.onLocalCountdownExpired()

            val resolved = vm.uiState.value as OfferUiState.Resolved
            assertEquals(OfferResolutionOutcome.Expired, resolved.outcome)
        }

    @Test
    fun localCountdownExpiryIsIgnoredWhileAnActionIsInFlight() =
        runTest {
            val vm = viewModel()
            val theOffer = offer()
            show(theOffer)
            advanceUntilIdle()
            coEvery { offerRepository.accept(theOffer.offerId!!) } coAnswers {
                delay(1_000)
                AcceptOfferOutcome.Accepted(BookingDetailResponse())
            }
            vm.onAccept()

            vm.onLocalCountdownExpired()
            advanceUntilIdle()

            // The in-flight accept's own response wins, not the local timer (design doc §3 rule 7).
            val resolved = vm.uiState.value as OfferUiState.Resolved
            assertTrue(resolved.outcome is OfferResolutionOutcome.Accepted)
        }

    @Test
    fun onResolutionConsumedReturnsToNone() =
        runTest {
            val vm = viewModel()
            val theOffer = offer()
            show(theOffer)
            advanceUntilIdle()
            vm.onLocalCountdownExpired()

            vm.onResolutionConsumed()

            assertEquals(OfferUiState.None, vm.uiState.value)
        }

    @Test
    fun aResolvedStateIsNotClobberedByTheSlotStillBeingOccupied() =
        runTest {
            val vm = viewModel()
            val theOffer = offer()
            show(theOffer)
            advanceUntilIdle()

            vm.onLocalCountdownExpired()
            advanceUntilIdle()

            // Even though nothing has cleared slotFlow itself in this test, the ViewModel's own
            // Resolved state must stick - it never re-derives Showing from an already-stale slot
            // value once it has moved past it.
            assertTrue(vm.uiState.value is OfferUiState.Resolved)
        }
}
