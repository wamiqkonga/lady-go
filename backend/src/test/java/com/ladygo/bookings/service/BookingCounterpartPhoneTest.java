package com.ladygo.bookings.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.bookings.web.dto.BookingDetailResponse;
import com.ladygo.customers.domain.Customer;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.locations.domain.City;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.users.domain.User;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Counterpart phone: rider sees the captain's, captain sees the rider's - only while accepted/active. */
class BookingCounterpartPhoneTest extends PersistenceTestSupport {

    @Autowired
    private BookingService bookingService;

    private static final List<BookingStatus> LIVE = List.of(
            BookingStatus.DRIVER_ACCEPTED, BookingStatus.DRIVER_ARRIVING,
            BookingStatus.DRIVER_ARRIVED, BookingStatus.RIDE_STARTED);
    private static final List<BookingStatus> NOT_LIVE = List.of(
            BookingStatus.SEARCHING_DRIVER, BookingStatus.DRIVER_ASSIGNED, BookingStatus.RIDE_COMPLETED,
            BookingStatus.PAYMENT_PENDING, BookingStatus.COMPLETED, BookingStatus.CANCELLED);

    private String phoneOf(java.util.UUID userId) {
        return users.findById(userId).map(User::getPhone).orElseThrow();
    }

    @Test
    void riderSeesCaptainPhoneAndCaptainSeesRiderPhoneOnlyWhileAcceptedOrActive() {
        City city = newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
        for (BookingStatus status : LIVE) {
            Customer customer = newCustomer();
            Driver driver = newDriver(city);
            newVehicle(driver);
            Booking booking = newBooking(customer, city, status, driver.getId());
            flushAndClear();

            BookingDetailResponse asRider = bookingService.get(customer.getUserId(), booking.getId());
            assertThat(asRider.driver().phone()).as("rider @ " + status).isEqualTo(phoneOf(driver.getUserId()));
            assertThat(asRider.riderPhone()).as("rider never gets own phone @ " + status).isNull();

            BookingDetailResponse asDriver = bookingService.getForDriver(driver.getUserId(), booking.getId());
            assertThat(asDriver.riderPhone()).as("captain @ " + status).isEqualTo(phoneOf(customer.getUserId()));
            assertThat(asDriver.driver()).isNull();
        }
    }

    @Test
    void phoneIsAbsentBeforeAcceptanceAndAfterCompletionOrCancellation() {
        City city = newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
        for (BookingStatus status : NOT_LIVE) {
            Customer customer = newCustomer();
            Driver driver = newDriver(city);
            newVehicle(driver);
            Booking booking = newBooking(customer, city, status, driver.getId());
            flushAndClear();

            BookingDetailResponse asRider = bookingService.get(customer.getUserId(), booking.getId());
            if (asRider.driver() != null) {
                assertThat(asRider.driver().phone()).as("rider @ " + status).isNull();
            }
            assertThat(asRider.riderPhone()).isNull();
            try {
                BookingDetailResponse asDriver = bookingService.getForDriver(driver.getUserId(), booking.getId());
                assertThat(asDriver.riderPhone()).as("captain @ " + status).isNull();
            } catch (RuntimeException notVisibleToDriver) {
                // the driver cannot even fetch the booking in this status - nothing to leak.
            }
        }
    }
}
