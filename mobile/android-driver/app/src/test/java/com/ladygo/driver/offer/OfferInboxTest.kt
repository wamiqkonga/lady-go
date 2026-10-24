package com.ladygo.driver.offer

import com.ladygo.driver.api.generated.models.OfferResponse
import com.ladygo.driver.realtime.ServerClockSkew
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class OfferInboxTest {
    private val skew = ServerClockSkew.NONE

    private fun offer(id: UUID = UUID.randomUUID()) = OfferResponse(offerId = id, estimatedFare = 100L)

    @Test
    fun aFreshOfferIsAccepted() {
        val inbox = OfferInbox()

        val accepted = inbox.onOfferReceived(offer(), skew)

        assertTrue(accepted)
        assertTrue(inbox.slot is OfferSlot.Occupied)
    }

    @Test
    fun aDuplicateDeliveryOfTheSameHeldOfferIsIgnored() {
        val inbox = OfferInbox()
        val theOffer = offer()
        inbox.onOfferReceived(theOffer, skew)

        val acceptedAgain = inbox.onOfferReceived(theOffer.copy(), skew)

        assertFalse(acceptedAgain)
    }

    @Test
    fun anOfferWithNoIdIsDropped() {
        val inbox = OfferInbox()

        val accepted = inbox.onOfferReceived(OfferResponse(offerId = null), skew)

        assertFalse(accepted)
        assertTrue(inbox.slot is OfferSlot.Empty)
    }

    @Test
    fun clearingTheCurrentOfferEmptiesTheSlot() {
        val inbox = OfferInbox()
        val id = UUID.randomUUID()
        inbox.onOfferReceived(offer(id), skew)

        val cleared = inbox.clearIfCurrent(id)

        assertTrue(cleared)
        assertTrue(inbox.slot is OfferSlot.Empty)
    }

    @Test
    fun clearingAMismatchedIdIsANoOp() {
        val inbox = OfferInbox()
        val heldId = UUID.randomUUID()
        inbox.onOfferReceived(offer(heldId), skew)

        val cleared = inbox.clearIfCurrent(UUID.randomUUID())

        assertFalse(cleared)
        assertTrue(inbox.slot is OfferSlot.Occupied)
    }

    @Test
    fun aLateDuplicateOfAnAlreadyResolvedOfferIsIgnoredEvenAfterTheSlotIsEmpty() {
        val inbox = OfferInbox()
        val id = UUID.randomUUID()
        val theOffer = offer(id)
        inbox.onOfferReceived(theOffer, skew)
        inbox.clearIfCurrent(id)

        // A late STOMP delivery or a stale reconciliation response for the same, already-resolved offer.
        val reAccepted = inbox.onOfferReceived(theOffer.copy(), skew)

        assertFalse(reAccepted)
        assertTrue(inbox.slot is OfferSlot.Empty)
    }

    @Test
    fun aDifferentOfferReplacesTheHeldOneDefensively() {
        val inbox = OfferInbox()
        inbox.onOfferReceived(offer(), skew)
        val secondOffer = offer()

        val accepted = inbox.onOfferReceived(secondOffer, skew)

        assertTrue(accepted)
        assertEquals(secondOffer.offerId, (inbox.slot as OfferSlot.Occupied).offer.offerId)
    }

    @Test
    fun resolvedIdsEvictOnceTheRecentHistoryCapIsExceeded() {
        val inbox = OfferInbox(maxRecentlyResolved = 2)
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val third = UUID.randomUUID()
        inbox.onOfferReceived(offer(first), skew)
        inbox.clearIfCurrent(first)
        inbox.onOfferReceived(offer(second), skew)
        inbox.clearIfCurrent(second)
        inbox.onOfferReceived(offer(third), skew)
        inbox.clearIfCurrent(third)

        // `first` has aged out of the bounded recently-resolved history, so it's treated as new again -
        // an accepted tradeoff (bounded memory) rather than an unbounded set of every offer id ever seen.
        val accepted = inbox.onOfferReceived(offer(first), skew)

        assertTrue(accepted)
    }
}
