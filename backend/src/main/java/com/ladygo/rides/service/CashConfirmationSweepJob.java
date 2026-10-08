package com.ladygo.rides.service;

import java.time.Duration;
import java.time.Instant;
import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.bookings.repository.BookingRepository;
import com.ladygo.bookings.service.BookingStateMachine;
import com.ladygo.common.domain.ActorType;
import com.ladygo.config.ConfigurationService;
import com.ladygo.payments.domain.PaymentMode;
import com.ladygo.rides.domain.RideFlagType;
import com.ladygo.rides.repository.RideFlagRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * docs/api/ride-lifecycle.md section 8 - auto-completes a cash booking once the rider's dispute
 * window has elapsed with no undisputed {@code CASH_DISPUTE} flag. Same DB-backed sweep shape as
 * {@code OfferSweepJob} - the system of record, works whether or not a push notification ever arrived.
 * {@code ONLINE} bookings are never touched here (no cash-collection fact exists to confirm against;
 * they're rejected at booking creation until Phase 8 anyway).
 */
@Component
public class CashConfirmationSweepJob {

    private final BookingRepository bookings;
    private final RideFlagRepository rideFlags;
    private final BookingStateMachine stateMachine;
    private final ConfigurationService config;

    public CashConfirmationSweepJob(
            BookingRepository bookings, RideFlagRepository rideFlags, BookingStateMachine stateMachine,
            ConfigurationService config) {
        this.bookings = bookings;
        this.rideFlags = rideFlags;
        this.stateMachine = stateMachine;
        this.config = config;
    }

    @Scheduled(fixedDelayString = "${ladygo.rides.cash_sweep.interval-ms:10000}")
    @Transactional
    public void sweep() {
        Duration window = config.getMinutes("ride.cash.dispute.window.minutes", Duration.ofMinutes(60));
        for (Booking booking : bookings.findByStatus(BookingStatus.PAYMENT_PENDING)) {
            if (booking.getPaymentMethod() != PaymentMode.CASH || booking.getCashConfirmedAt() == null) {
                continue;
            }
            if (Duration.between(booking.getCashConfirmedAt(), Instant.now()).compareTo(window) < 0) {
                continue; // dispute window still open.
            }
            if (!rideFlags.findByBookingIdAndType(booking.getId(), RideFlagType.CASH_DISPUTE).isEmpty()) {
                continue; // disputed - waits on admin resolution (Phase 9), not the clock.
            }
            stateMachine.transition(booking.getId(), BookingStatus.COMPLETED, ActorType.SYSTEM, null,
                    "cash_confirmed_auto_complete");
        }
    }
}
