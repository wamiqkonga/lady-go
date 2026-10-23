package com.ladygo.driver.offer

import com.ladygo.driver.api.generated.models.OfferResponse
import com.ladygo.driver.di.AppScope
import com.ladygo.driver.realtime.RealtimeEvent
import com.ladygo.driver.realtime.RealtimeEvents
import com.ladygo.driver.realtime.ServerClockSkew
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single, process-lifetime owner of "is there a current offer" - dedupe ([OfferInbox]) and the
 * high-priority notification both happen here, not in [OfferViewModel], because [OfferViewModel] is
 * Activity-scoped and gets cleared when the Activity is destroyed (e.g. the task is swiped away),
 * while the design doc's whole premise (§2.3) is that the foreground service and this app's one STOMP
 * connection keep running after that happens. Before this existed, an offer pushed while no Activity
 * was alive was silently dropped by [RealtimeEvents]' no-replay `SharedFlow` - no notification, no
 * sound, nothing (fresh-context review finding, CRITICAL). Eagerly constructed from
 * [com.ladygo.driver.LadyGoDriverApplication] so it's always collecting from process start, the same
 * reasoning [com.ladygo.driver.realtime.StompConnectionManager] already relies on for its own
 * lifetime.
 */
@Singleton
class OfferCoordinator
    @Inject
    constructor(
        private val realtimeEvents: RealtimeEvents,
        private val offerNotifier: OfferNotifier,
        @AppScope scope: CoroutineScope,
    ) {
        private val offerInbox = OfferInbox()
        private val _slot = MutableStateFlow<OfferSlot>(OfferSlot.Empty)
        val slot: StateFlow<OfferSlot> = _slot.asStateFlow()

        init {
            // A notification left over from a previous process is stale: the slot starts empty.
            offerNotifier.cancel()
            scope.launch {
                realtimeEvents.events.collect { event ->
                    if (event is RealtimeEvent.OfferReceived) handlePushedOffer(event.offer, event.skew)
                }
            }
        }

        private fun handlePushedOffer(
            offer: OfferResponse,
            skew: ServerClockSkew,
        ) {
            if (!offerInbox.onOfferReceived(offer, skew)) return
            _slot.value = OfferSlot.Occupied(offer, skew)
            offerNotifier.notify(offer)
        }

        /** [OfferViewModel]'s reconciliation fetch (`GET .../offers/current`) routes a present offer
         *  through here too, so a push that was missed (e.g. no Activity was alive to show it) still
         *  gets deduped and notified exactly once when the reconcile catches it. */
        fun onReconciledOffer(
            offer: OfferResponse,
            skew: ServerClockSkew,
        ) = handlePushedOffer(offer, skew)

        /** The server no longer has a current offer - if we're still holding one, it was resolved
         *  some other way while nothing was watching (cancelled, claimed, cleaned up); the server wins. */
        fun onReconciledNone() {
            // Always: a notification can outlive the slot (swiped-away app, push dropped), and the
            // server says there is no offer.
            offerNotifier.cancel()
            val current = (_slot.value as? OfferSlot.Occupied)?.offer?.offerId ?: return
            if (offerInbox.clearIfCurrent(current)) _slot.value = OfferSlot.Empty
        }

        /** Called once an offer is resolved (accepted/declined/expired/no-longer-available) so the
         *  dedupe history and the notification both clear together - there is exactly one place that
         *  decides "is there currently an offer," shared by the always-alive notifier path and the UI. */
        fun clearIfCurrent(offerId: UUID) {
            if (offerInbox.clearIfCurrent(offerId)) {
                _slot.value = OfferSlot.Empty
                offerNotifier.cancel()
            }
        }
    }
