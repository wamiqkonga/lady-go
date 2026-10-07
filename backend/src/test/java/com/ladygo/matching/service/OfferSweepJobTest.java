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

    /** Adversarial review finding, HIGH: the sweep used to unconditionally overwrite whatever offer row
     *  it selected back to EXPIRED and release the driver, with no check that the offer was still
     *  OFFERED by the time the sweep's own UPDATE actually ran - a real race window, since the sweep's
     *  SELECT (by status=OFFERED + expiresAt) and its UPDATE are two separate statements, and a
     *  driver's accept() can commit in between. That interleaving can't be reproduced deterministically
     *  in a single-threaded test, so this exercises the actual guard
     *  (RideOfferRepository.expireIfStillOffered) the fix added, the same way OfferSweepJob itself now
     *  calls it: given an offer that has ALREADY been accepted (simulating accept() having won that
     *  race) and whose expiresAt has passed, the guarded expire must affect 0 rows and leave the
     *  driver/booking exactly as accept() left them - not silently release an already-committed
     *  driver back to ONLINE where an unrelated booking's search could then claim them mid-ride. */
    @Test
    void expireIfStillOfferedRefusesToOverwriteAnAlreadyAcceptedOffer() {
        City city = srinagar();
        Customer customer = newCustomer();
        Driver driver = newOnlineDriver(city, 74.80, 34.08);
        driver.setAvailabilityStatus(DriverAvailability.BUSY);
        drivers.saveAndFlush(driver);
        Booking booking = newBooking(customer, city, BookingStatus.DRIVER_ACCEPTED, driver.getId());
        RideOffer offer = new RideOffer();
        offer.setBookingId(booking.getId());
        offer.setDriverId(driver.getId());
        offer.setStatus(OfferStatus.ACCEPTED); // accept() already won the race
        offer.setExpiresAt(Instant.now().minusSeconds(5)); // but a sweep tick would still have selected it
        offers.saveAndFlush(offer);

        int rowsAffected = offers.expireIfStillOffered(offer.getId(), Instant.now());
        flushAndClear();

        assertThat(rowsAffected).isZero();
        assertThat(offers.findById(offer.getId()).orElseThrow().getStatus()).isEqualTo(OfferStatus.ACCEPTED);
        assertThat(drivers.findById(driver.getId()).orElseThrow().getAvailabilityStatus())
                .isEqualTo(DriverAvailability.BUSY); // never released - still genuinely committed
        assertThat(bookings.findById(booking.getId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.DRIVER_ACCEPTED);
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
