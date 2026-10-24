package com.ladygo.rides.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.customers.domain.Customer;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.locations.domain.City;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import com.ladygo.testsupport.PersistenceTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** docs/api/ride-lifecycle.md section 8 - the DB-backed auto-complete sweep. */
class CashConfirmationSweepJobTest extends PersistenceTestSupport {

    @Autowired
    private CashConfirmationSweepJob job;
    @Autowired
    private RideLifecycleService rideLifecycleService;

    private City srinagar() {
        return newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
    }

    private Booking paymentPendingCashBooking(Instant cashConfirmedAt) {
        City city = srinagar();
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.PAYMENT_PENDING, driver.getId());
        booking.setCashConfirmedAt(cashConfirmedAt);
        return bookings.saveAndFlush(booking);
    }

    @Test
    void aBookingPastTheDisputeWindowWithNoDisputeAutoCompletes() {
        Booking booking = paymentPendingCashBooking(Instant.now().minus(2, ChronoUnit.HOURS));

        job.sweep();
        flushAndClear();

        assertThat(bookings.findById(booking.getId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.COMPLETED);
    }

    @Test
    void aBookingStillWithinTheDisputeWindowIsLeftAlone() {
        Booking booking = paymentPendingCashBooking(Instant.now().minus(5, ChronoUnit.MINUTES));

        job.sweep();
        flushAndClear();

        assertThat(bookings.findById(booking.getId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.PAYMENT_PENDING);
    }

    /** Security review finding, HIGH: the gate is the booking's own {@code cashDisputedAt} column
     *  (bumping its {@code @Version}, so a real concurrent dispute loses to this sweep with an
     *  {@code OptimisticLockingFailureException} rather than silently - see RideLifecycleService.
     *  disputeCash/autoCompleteCashBookingIfUndisputed), not a separate RideFlag query - this test
     *  proves the functional gate; the true concurrent race isn't simulated here (same acknowledged
     *  limitation as OfferSweepJobTest's own equivalent race fix - not deterministically reproducible
     *  single-threaded), only that @Version is what the two sides now actually share. */
    @Test
    void aDisputedBookingIsNeverAutoCompleted() {
        Booking booking = paymentPendingCashBooking(Instant.now().minus(2, ChronoUnit.HOURS));
        booking.setCashDisputedAt(Instant.now());
        bookings.saveAndFlush(booking);

        job.sweep();
        flushAndClear();

        assertThat(bookings.findById(booking.getId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.PAYMENT_PENDING);
    }

    /** Explicit invariant check (item 4 of the ride-lifecycle security follow-up): the sweep's own
     *  completion action is conditional and idempotent - running it again against a booking it
     *  already completed must be a safe no-op, never a duplicate transition or error. */
    @Test
    void sweepingAnAlreadyCompletedBookingAgainIsASafeNoOp() {
        Booking booking = paymentPendingCashBooking(Instant.now().minus(2, ChronoUnit.HOURS));

        job.sweep();
        flushAndClear();
        assertThat(bookings.findById(booking.getId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.COMPLETED);

        job.sweep(); // second tick, same booking - already COMPLETED, not PAYMENT_PENDING anymore.
        flushAndClear();

        assertThat(bookings.findById(booking.getId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.COMPLETED);
    }

    /** Same invariant, exercised directly against the per-booking method rather than through the
     *  status filter that makes the sweep-level test above almost trivially safe - this proves
     *  autoCompleteCashBookingIfUndisputed's own internal status re-check (not just the candidate
     *  query) is what makes a repeat call on an already-COMPLETED booking a safe no-op. */
    @Test
    void autoCompleteIsIdempotentWhenCalledDirectlyOnAnAlreadyCompletedBooking() {
        Booking booking = paymentPendingCashBooking(Instant.now().minus(2, ChronoUnit.HOURS));

        rideLifecycleService.autoCompleteCashBookingIfUndisputed(booking.getId());
        flushAndClear();
        assertThat(bookings.findById(booking.getId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.COMPLETED);

        assertThatCode(() -> rideLifecycleService.autoCompleteCashBookingIfUndisputed(booking.getId()))
                .doesNotThrowAnyException();
        flushAndClear();
        assertThat(bookings.findById(booking.getId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.COMPLETED);
    }

    @Test
    void aBookingNeverConfirmedByTheDriverIsLeftAlone() {
        City city = srinagar();
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.PAYMENT_PENDING, driver.getId()); // no cashConfirmedAt.

        job.sweep();
        flushAndClear();

        assertThat(bookings.findById(booking.getId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.PAYMENT_PENDING);
    }
}
