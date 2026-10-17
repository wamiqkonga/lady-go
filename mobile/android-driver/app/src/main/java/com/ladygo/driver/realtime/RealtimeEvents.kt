package com.ladygo.driver.realtime

import com.ladygo.driver.api.generated.models.BookingDetailResponse
import com.ladygo.driver.api.generated.models.OfferResponse
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed interface RealtimeEvent {
    /** A genuinely new offer (already deduped by [com.ladygo.driver.offer.OfferInbox]) pushed over
     *  `/user/queue/offers`, or surfaced by the reconciliation fetch on (re)connect. */
    data class OfferReceived(
        val offer: OfferResponse,
        val skew: ServerClockSkew,
    ) : RealtimeEvent

    /** Pushed over `/user/queue/booking` - also used driver-side for ride-state updates
     *  (`BookingService.pushToUser(driver.getUserId(), "/queue/booking", ...)`), not just the
     *  customer-side push. Consumed by the slice-3 ride loop; this slice only stubs that hand-off. */
    data class BookingUpdated(
        val booking: BookingDetailResponse,
    ) : RealtimeEvent

    /** The STOMP connection (re)established and its reconnect-reconciliation subscriptions are live -
     *  the signal consumers use to re-fetch current server state (design doc §2.2/§3 rule 2), since a
     *  push message missed while disconnected is never specially recovered. Carries the clock-skew
     *  sample taken for this connection, so a reconciled offer's countdown can reuse it instead of
     *  defaulting to no correction at all. */
    data class Connected(
        val skew: ServerClockSkew,
    ) : RealtimeEvent

    data object Disconnected : RealtimeEvent
}

/** Lets [com.ladygo.driver.realtime.StompConnectionManager] (which has no ViewModel of its own) tell
 *  whichever ViewModel cares about something it observed, without either side holding a reference to
 *  the other - same shape as [com.ladygo.driver.location.LocationServiceEvents]. */
@Singleton
class RealtimeEvents
    @Inject
    constructor() {
        private val _events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 8)
        val events: SharedFlow<RealtimeEvent> = _events.asSharedFlow()

        suspend fun emit(event: RealtimeEvent) {
            _events.emit(event)
        }
    }
