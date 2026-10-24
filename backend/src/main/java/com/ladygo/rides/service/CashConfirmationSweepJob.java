package com.ladygo.rides.service;

import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.bookings.repository.BookingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * docs/api/ride-lifecycle.md section 8 - auto-completes a cash booking once the rider's dispute
 * window has elapsed with no dispute. Same DB-backed sweep shape as {@code OfferSweepJob} - the
 * system of record, works whether or not a push notification ever arrived. {@code ONLINE} bookings
 * are never touched here (no cash-collection fact exists to confirm against; they're rejected at
 * booking creation until Phase 8 anyway).
 *
 * <p>Security review finding, HIGH: each candidate is now completed via a genuinely separate bean
 * call ({@link RideLifecycleService#autoCompleteCashBookingIfUndisputed}, its own transaction per
 * booking - calling a {@code @Transactional} method on {@code this} wouldn't go through Spring's AOP
 * proxy and would silently not be transactional at all) rather than one big transaction for the whole
 * sweep. That method's gate is the booking's own {@code cashDisputedAt} column, which a concurrent
 * {@link RideLifecycleService#disputeCash} now bumps the {@code @Version} of - so a dispute landing
 * mid-sweep makes this booking's completion attempt fail with an
 * {@code OptimisticLockingFailureException} instead of silently losing the race. One booking's
 * conflict is caught and skipped (same resilience shape as {@code OfferSweepJob}'s own
 * concurrent-accept handling) rather than aborting the rest of the batch.
 *
 * <p>Security review finding (fail-open-state-drift): catching only {@code
 * OptimisticLockingFailureException} meant any OTHER unexpected failure on one booking (e.g. a
 * genuinely missing row) propagated out of the loop and aborted the whole tick - every other
 * eligible booking in that same candidate list silently failed to auto-complete too, not because
 * anything was wrong with THEM, just because they were later in the list than the one that threw.
 * Each candidate's {@code RuntimeException} is now caught individually so one booking's problem never
 * blocks another's.
 */
@Component
public class CashConfirmationSweepJob {

    private static final Logger log = LoggerFactory.getLogger(CashConfirmationSweepJob.class);

    private final BookingRepository bookings;
    private final RideLifecycleService rideLifecycleService;

    public CashConfirmationSweepJob(BookingRepository bookings, RideLifecycleService rideLifecycleService) {
        this.bookings = bookings;
        this.rideLifecycleService = rideLifecycleService;
    }

    @Scheduled(fixedDelayString = "${ladygo.rides.cash_sweep.interval-ms:10000}")
    public void sweep() {
        for (Booking candidate : bookings.findByStatus(BookingStatus.PAYMENT_PENDING)) {
            try {
                rideLifecycleService.autoCompleteCashBookingIfUndisputed(candidate.getId());
            } catch (OptimisticLockingFailureException ex) {
                log.info("operation=cash_auto_complete booking_id={} result=skipped reason=concurrent_update",
                        candidate.getId());
            } catch (RuntimeException ex) {
                log.warn("operation=cash_auto_complete booking_id={} result=failed reason=unexpected_error",
                        candidate.getId(), ex);
            }
        }
    }
}
