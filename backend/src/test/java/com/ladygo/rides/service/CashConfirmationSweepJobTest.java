package com.ladygo.rides.service;

import static org.assertj.core.api.Assertions.assertThat;

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
