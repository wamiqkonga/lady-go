package com.ladygo.rides.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.common.domain.ActorType;
import com.ladygo.customers.domain.Customer;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.locations.domain.City;
import com.ladygo.rides.domain.RideFlag;
import com.ladygo.rides.domain.RideFlagType;
import com.ladygo.rides.repository.RideFlagRepository;
import java.sql.Timestamp;
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
    private RideFlagRepository rideFlags;

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

    @Test
    void aDisputedBookingIsNeverAutoCompleted() {
        Booking booking = paymentPendingCashBooking(Instant.now().minus(2, ChronoUnit.HOURS));
        RideFlag flag = new RideFlag();
        flag.setBookingId(booking.getId());
        flag.setCustomerId(booking.getCustomerId());
        flag.setDriverId(booking.getDriverId());
        flag.setType(RideFlagType.CASH_DISPUTE);
        flag.setRaisedBy(ActorType.CUSTOMER);
        rideFlags.saveAndFlush(flag);

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
