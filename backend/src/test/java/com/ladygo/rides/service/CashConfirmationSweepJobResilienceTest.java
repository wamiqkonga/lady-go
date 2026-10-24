package com.ladygo.rides.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.customers.domain.Customer;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.locations.domain.City;
import com.ladygo.testsupport.PersistenceTestSupport;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Security review finding (fail-open-state-drift): {@link CashConfirmationSweepJob} used to catch
 * only {@code OptimisticLockingFailureException} - any OTHER unexpected failure on one booking
 * propagated out of the loop and aborted the whole tick, silently skipping every OTHER eligible
 * booking in that same batch too. {@link RideLifecycleService} is mocked here (a separate test class
 * from {@link CashConfirmationSweepJobTest}, which needs the real one) so one candidate can be made
 * to throw a generic, non-optimistic-lock exception deterministically, regardless of query ordering.
 */
class CashConfirmationSweepJobResilienceTest extends PersistenceTestSupport {

    @Autowired
    private CashConfirmationSweepJob job;
    @MockitoBean
    private RideLifecycleService rideLifecycleService;

    private Booking paymentPendingCashBooking() {
        City city = newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.PAYMENT_PENDING, driver.getId());
        booking.setCashConfirmedAt(Instant.now().minus(2, ChronoUnit.HOURS));
        return bookings.saveAndFlush(booking);
    }

    @Test
    void oneBookingsUnexpectedFailureDoesNotBlockTheOthersInTheSameTick() {
        Booking broken = paymentPendingCashBooking();
        Booking healthy = paymentPendingCashBooking();
        doThrow(new RuntimeException("simulated unexpected failure"))
                .when(rideLifecycleService).autoCompleteCashBookingIfUndisputed(eq(broken.getId()));

        assertThatCode(() -> job.sweep()).doesNotThrowAnyException();

        verify(rideLifecycleService).autoCompleteCashBookingIfUndisputed(broken.getId());
        verify(rideLifecycleService).autoCompleteCashBookingIfUndisputed(healthy.getId());
    }
}
