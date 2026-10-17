package com.ladygo.driver.offer

import com.ladygo.driver.api.generated.models.OfferResponse
import com.ladygo.driver.realtime.RealtimeEvent
import com.ladygo.driver.realtime.RealtimeEvents
import com.ladygo.driver.realtime.ServerClockSkew
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class OfferCoordinatorTest {
    private val realtimeEvents = RealtimeEvents()
    private val offerNotifier = mockk<OfferNotifier>(relaxed = true)

    /** A fresh [Job], not this test's own root job - this class's `init` block launches a
     *  permanently-running collector (by design: it must survive for the whole process, same as
     *  [com.ladygo.driver.realtime.StompConnectionManager]'s own observer), which `runTest` would
     *  otherwise flag as an "uncompleted coroutine" leak. */
    private fun TestScope.coordinator(): OfferCoordinator {
        val coordinator = OfferCoordinator(realtimeEvents, offerNotifier, CoroutineScope(coroutineContext + Job()))
        advanceUntilIdle() // let the init-block collector actually subscribe before the first emit below
        return coordinator
    }

    private fun offer(id: UUID = UUID.randomUUID()) = OfferResponse(offerId = id, estimatedFare = 200L, currency = "PKR")

    @Test
    fun aPushedOfferOccupiesTheSlotAndNotifies() =
        runTest {
            val coordinator = coordinator()
            val theOffer = offer()

            realtimeEvents.emit(RealtimeEvent.OfferReceived(theOffer, ServerClockSkew.NONE))
            advanceUntilIdle()

            assertTrue(coordinator.slot.value is OfferSlot.Occupied)
            assertEquals(theOffer, (coordinator.slot.value as OfferSlot.Occupied).offer)
            verify(exactly = 1) { offerNotifier.notify(theOffer) }
        }

    @Test
    fun aDuplicatePushOfTheSameOfferIsIgnored() =
        runTest {
            val coordinator = coordinator()
            val theOffer = offer()
            realtimeEvents.emit(RealtimeEvent.OfferReceived(theOffer, ServerClockSkew.NONE))
            advanceUntilIdle()

            realtimeEvents.emit(RealtimeEvent.OfferReceived(theOffer.copy(), ServerClockSkew.NONE))
            advanceUntilIdle()

            verify(exactly = 1) { offerNotifier.notify(any()) }
        }

    @Test
    fun bookingUpdatedEventsAreIgnored() =
        runTest {
            val coordinator = coordinator()

            realtimeEvents.emit(
                RealtimeEvent.BookingUpdated(
                    com.ladygo.driver.api.generated.models
                        .BookingDetailResponse(),
                ),
            )
            advanceUntilIdle()

            assertTrue(coordinator.slot.value is OfferSlot.Empty)
        }

    @Test
    fun onReconciledOfferGoesThroughTheSameDedupeAsAPush() =
        runTest {
            val coordinator = coordinator()
            val theOffer = offer()
            realtimeEvents.emit(RealtimeEvent.OfferReceived(theOffer, ServerClockSkew.NONE))
            advanceUntilIdle()

            coordinator.onReconciledOffer(theOffer.copy(), ServerClockSkew.NONE)

            // Still the same, already-notified offer - a reconcile re-delivering it is a no-op, not a
            // second notification.
            verify(exactly = 1) { offerNotifier.notify(any()) }
        }

    @Test
    fun onReconciledNoneClearsAHeldOffer() =
        runTest {
            val coordinator = coordinator()
            val theOffer = offer()
            realtimeEvents.emit(RealtimeEvent.OfferReceived(theOffer, ServerClockSkew.NONE))
            advanceUntilIdle()

            coordinator.onReconciledNone()

            assertTrue(coordinator.slot.value is OfferSlot.Empty)
            verify(exactly = 1) { offerNotifier.cancel() }
        }

    @Test
    fun onReconciledNoneIsANoOpWhenNothingIsHeld() =
        runTest {
            val coordinator = coordinator()

            coordinator.onReconciledNone()

            verify(exactly = 0) { offerNotifier.cancel() }
        }

    @Test
    fun clearIfCurrentClearsTheSlotAndCancelsTheNotification() =
        runTest {
            val coordinator = coordinator()
            val theOffer = offer()
            realtimeEvents.emit(RealtimeEvent.OfferReceived(theOffer, ServerClockSkew.NONE))
            advanceUntilIdle()

            coordinator.clearIfCurrent(theOffer.offerId!!)

            assertTrue(coordinator.slot.value is OfferSlot.Empty)
            verify(exactly = 1) { offerNotifier.cancel() }
        }

    @Test
    fun clearIfCurrentWithAMismatchedIdIsANoOp() =
        runTest {
            val coordinator = coordinator()
            val theOffer = offer()
            realtimeEvents.emit(RealtimeEvent.OfferReceived(theOffer, ServerClockSkew.NONE))
            advanceUntilIdle()

            coordinator.clearIfCurrent(UUID.randomUUID())

            assertFalse(coordinator.slot.value is OfferSlot.Empty)
            verify(exactly = 0) { offerNotifier.cancel() }
        }

    @Test
    fun aLateDuplicateOfAnAlreadyResolvedOfferDoesNotReoccupyTheSlot() =
        runTest {
            val coordinator = coordinator()
            val theOffer = offer()
            realtimeEvents.emit(RealtimeEvent.OfferReceived(theOffer, ServerClockSkew.NONE))
            advanceUntilIdle()
            coordinator.clearIfCurrent(theOffer.offerId!!)

            realtimeEvents.emit(RealtimeEvent.OfferReceived(theOffer.copy(), ServerClockSkew.NONE))
            advanceUntilIdle()

            assertTrue(coordinator.slot.value is OfferSlot.Empty)
            verify(exactly = 1) { offerNotifier.notify(any()) }
        }
}
