package com.ladygo.matching.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.bookings.domain.OfferStatus;
import com.ladygo.bookings.domain.RideOffer;
import com.ladygo.bookings.repository.RideOfferRepository;
import com.ladygo.customers.domain.Customer;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.domain.DriverAvailability;
import com.ladygo.locations.domain.City;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.vehicles.domain.Vehicle;
import com.ladygo.vehicles.domain.VehicleType;
import com.ladygo.vehicles.domain.VehicleVerificationStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** docs/api/matching.md section 4/5 - the DB-backed sweep, the system of record for offer
 *  expiry/exhaustion regardless of whether any WebSocket/Redis push ever arrived. */
class OfferSweepJobTest extends PersistenceTestSupport {

    @Autowired
    private OfferSweepJob job;
    @Autowired
    private RideOfferRepository offers;

    private City srinagar() {
        return newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
    }

    @Test
    void anExpiredOfferIsFreedAndTheBookingReturnsToSearching() {
        City city = srinagar();
        Customer customer = newCustomer();
        Driver driver = newOnlineDriver(city, 74.80, 34.08);
        Booking booking = newBooking(customer, city, BookingStatus.DRIVER_ASSIGNED, driver.getId());
        driver.setAvailabilityStatus(DriverAvailability.BUSY);
        drivers.saveAndFlush(driver);
        RideOffer offer = new RideOffer();
        offer.setBookingId(booking.getId());
        offer.setDriverId(driver.getId());
        offer.setStatus(OfferStatus.OFFERED);
        offer.setExpiresAt(Instant.now().minusSeconds(5));
        offers.saveAndFlush(offer);

        job.sweep();
        flushAndClear();

        assertThat(offers.findById(offer.getId()).orElseThrow().getStatus()).isEqualTo(OfferStatus.EXPIRED);
        assertThat(drivers.findById(driver.getId()).orElseThrow().getAvailabilityStatus())
                .isEqualTo(DriverAvailability.ONLINE);
        assertThat(bookings.findById(booking.getId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.SEARCHING_DRIVER);
    }

    @Test
    void aBookingPastTheMaxSearchTimeIsCancelledAsNoDriverFound() {
        jdbc.update("UPDATE configuration SET value = '0' WHERE key = 'ride.max.search.time.s'");
        City city = srinagar();
        Customer customer = newCustomer();
        Booking booking = newBooking(customer, city, BookingStatus.SEARCHING_DRIVER, null);

        job.sweep();
        flushAndClear();

        Booking reloaded = bookings.findById(booking.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(reloaded.getCancelReason()).isEqualTo("NO_DRIVER_FOUND");
    }

    @Test
    void aBookingThatHasExhaustedMatchingMaxOffersIsCancelled() {
        jdbc.update("UPDATE configuration SET value = '1' WHERE key = 'matching.max.offers'");
        City city = srinagar();
        Customer customer = newCustomer();
        Driver triedAlready = newOnlineDriver(city, 74.80, 34.08);
        Booking booking = newBooking(customer, city, BookingStatus.SEARCHING_DRIVER, null);
        RideOffer rejected = new RideOffer();
        rejected.setBookingId(booking.getId());
        rejected.setDriverId(triedAlready.getId());
        rejected.setStatus(OfferStatus.REJECTED);
        offers.saveAndFlush(rejected);

        job.sweep();
        flushAndClear();

        assertThat(bookings.findById(booking.getId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    void aBookingWithACandidateStillAvailableIsNotCancelledEarly() {
        City city = srinagar();
        Customer customer = newCustomer();
        Driver driver = newOnlineDriver(city, 74.80, 34.08);
        Vehicle vehicle = newVehicle(driver);
        vehicle.setVerificationStatus(VehicleVerificationStatus.APPROVED);
        vehicle.setVehicleType(VehicleType.CAB);
        vehicles.saveAndFlush(vehicle);
        Booking booking = newBooking(customer, city, BookingStatus.SEARCHING_DRIVER, null);

        job.sweep();
        flushAndClear();

        assertThat(bookings.findById(booking.getId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.DRIVER_ASSIGNED); // the sweep found and offered the candidate
    }
}
