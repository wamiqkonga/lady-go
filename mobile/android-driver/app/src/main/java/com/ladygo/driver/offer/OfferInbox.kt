package com.ladygo.driver.offer

import com.ladygo.driver.api.generated.models.OfferResponse
import com.ladygo.driver.realtime.ServerClockSkew
import java.util.UUID

sealed interface OfferSlot {
    data object Empty : OfferSlot

    data class Occupied(
        val offer: OfferResponse,
        val skew: ServerClockSkew,
    ) : OfferSlot
}

/**
 * Pure "at most one offer, ignore duplicate deliveries" gate - no Android/ViewModel dependency, same
 * testable-without-Robolectric style as [com.ladygo.driver.location.LocationUploadGate]. Both a STOMP
 * push and the `GET .../offers/current` reconciliation fetch feed into [onOfferReceived], so both
 * sources are deduped identically (design doc §1/§2.2: "ignore duplicate offer deliveries, idempotent
 * by offer id").
 *
 * A different offer id arriving while one is already held *replaces* it rather than being rejected -
 * defensive only. Under normal server behavior this driver is already BUSY the moment an offer is
 * sent, so a second, different offer should never actually arrive while one is pending; this is a
 * deliberate "don't crash on an anomaly" choice, not a designed concurrent-offers feature.
 */
class OfferInbox(
    private val maxRecentlyResolved: Int = 20,
) {
    private var current: OfferSlot = OfferSlot.Empty
    private val recentlyResolvedIds = ArrayDeque<UUID>()

    val slot: OfferSlot get() = current

    /** Returns true if this is a new offer the caller should actually show/notify for - false for a
     *  duplicate delivery of the offer already held, or a stale/late duplicate of one already
     *  resolved (accepted/declined/expired/claimed/cancelled). A malformed offer with no id is
     *  dropped (returns false) rather than shown with nothing to dedupe or resolve it by. */
    fun onOfferReceived(
        offer: OfferResponse,
        skew: ServerClockSkew,
    ): Boolean {
        val offerId = offer.offerId ?: return false
        if (offerId in recentlyResolvedIds) return false
        val existing = current
        if (existing is OfferSlot.Occupied && existing.offer.offerId == offerId) return false
        current = OfferSlot.Occupied(offer, skew)
        return true
    }

    /** Ends this offer's lifetime (accept/decline/expiry/already-claimed/cancelled all call this) -
     *  a no-op if [offerId] doesn't match the currently held offer (e.g. a stale resolution racing a
     *  newer offer that has since replaced it), so it never clears the wrong one. Returns whether it
     *  actually cleared anything. */
    fun clearIfCurrent(offerId: UUID): Boolean {
        val existing = current
        if (existing is OfferSlot.Occupied && existing.offer.offerId == offerId) {
            current = OfferSlot.Empty
            rememberResolved(offerId)
            return true
        }
        return false
    }

    private fun rememberResolved(id: UUID) {
        recentlyResolvedIds.addLast(id)
        while (recentlyResolvedIds.size > maxRecentlyResolved) {
            recentlyResolvedIds.removeFirst()
        }
    }
}
