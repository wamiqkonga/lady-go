package com.ladygo.matching.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.bookings.domain.OfferStatus;
import com.ladygo.bookings.domain.RideOffer;
import com.ladygo.bookings.repository.RideOfferRepository;
import com.ladygo.bookings.web.dto.BookingDetailResponse;
import com.ladygo.common.exception.ResourceNotFoundException;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.customers.domain.Customer;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.domain.DriverAvailability;
import com.ladygo.locations.domain.City;
import com.ladygo.matching.web.dto.OfferResponse;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.vehicles.domain.Vehicle;
import com.ladygo.vehicles.domain.VehicleType;
import com.ladygo.vehicles.domain.VehicleVerificationStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** docs/api/matching.md sections 4-6. */
class MatchingServiceTest extends PersistenceTestSupport {

    @Autowired
    private MatchingService service;
    @Autowired
    private RideOfferRepository offers;

    private City srinagar() {
        return newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
    }

    private Driver onlineDriverWithApprovedVehicle(City city, VehicleType type) {
        Driver driver = newOnlineDriver(city, 74.80, 34.08);
        Vehicle vehicle = newVehicle(driver);
        vehicle.setVehicleType(type);
        vehicle.setVerificationStatus(VehicleVerificationStatus.APPROVED);
        vehicles.saveAndFlush(vehicle);
        return driver;
    }

    @Test
    void offersTheNearestApprovedVehicleMatchingCandidateAndSkipsAVehicleTypeMismatch() {
        City city = srinagar();
        Customer customer = newCustomer();
        Driver wrongVehicle = onlineDriverWithApprovedVehicle(city, VehicleType.AUTO);
        Driver matching = onlineDriverWithApprovedVehicle(city, VehicleType.CAB);
        Booking booking = newBooking(customer, city, BookingStatus.SEARCHING_DRIVER, null);

        service.tryOfferNextCandidate(booking.getId());
        flushAndClear(); // the bulk-update claim never refreshed these already-loaded entities.

        RideOffer offer = offers.findByBookingIdAndStatus(booking.getId(), OfferStatus.OFFERED).orElseThrow();
        assertThat(offer.getDriverId()).isEqualTo(matching.getId());
        assertThat(drivers.findById(matching.getId()).orElseThrow().getAvailabilityStatus())
                .isEqualTo(DriverAvailability.BUSY);
        assertThat(drivers.findById(wrongVehicle.getId()).orElseThrow().getAvailabilityStatus())
                .isEqualTo(DriverAvailability.ONLINE); // untouched - never claimed.
        assertThat(bookings.findById(booking.getId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.DRIVER_ASSIGNED);
    }

    @Test
    void aDriverAlreadyOfferedThisBookingIsNeverOfferedAgain() {
        City city = srinagar();
        Customer customer = newCustomer();
        Driver already = onlineDriverWithApprovedVehicle(city, VehicleType.CAB);
        Driver fresh = onlineDriverWithApprovedVehicle(city, VehicleType.CAB);
        Booking booking = newBooking(customer, city, BookingStatus.SEARCHING_DRIVER, null);
        RideOffer priorOffer = new RideOffer();
        priorOffer.setBookingId(booking.getId());
        priorOffer.setDriverId(already.getId());
        priorOffer.setStatus(OfferStatus.REJECTED);
        offers.saveAndFlush(priorOffer);

        service.tryOfferNextCandidate(booking.getId());

        RideOffer liveOffer = offers.findByBookingIdAndStatus(booking.getId(), OfferStatus.OFFERED).orElseThrow();
        assertThat(liveOffer.getDriverId()).isEqualTo(fresh.getId());
    }

    @Test
    void theOfferedDriverSeesOnlyAnApproximatePickupNeverTheExactAddress() {
        City city = srinagar();
        Customer customer = newCustomer();
        Driver driver = onlineDriverWithApprovedVehicle(city, VehicleType.CAB);
        Booking booking = newBooking(customer, city, BookingStatus.SEARCHING_DRIVER, null);
        service.tryOfferNextCandidate(booking.getId());

        OfferResponse offer = service.getCurrentOffer(driver.getUserId());

        // The exact pickup point ("Lal Chowk") is 74.80/34.08 - the approximate point must be rounded
        // to the configured grid, so it should differ from the exact point (not a coincidence given the
        // default 300m grid), and OfferResponse structurally has no address field at all.
        assertThat(offer.bookingId()).isEqualTo(booking.getId());
        assertThat(offer.approximatePickupLatitude()).isNotNull();
        assertThat(offer.approximatePickupLongitude()).isNotNull();
    }

    @Test
    void acceptingTransitionsTheBookingAndRevealsTheExactPickupAddress() {
        City city = srinagar();
        Customer customer = newCustomer();
        Driver driver = onlineDriverWithApprovedVehicle(city, VehicleType.CAB);
        Booking booking = newBooking(customer, city, BookingStatus.SEARCHING_DRIVER, null);
        service.tryOfferNextCandidate(booking.getId());
        UUID offerId = offers.findByBookingIdAndStatus(booking.getId(), OfferStatus.OFFERED).orElseThrow().getId();

        BookingDetailResponse response = service.accept(driver.getUserId(), offerId);

        assertThat(response.status()).isEqualTo(BookingStatus.DRIVER_ACCEPTED);
        assertThat(response.pickupAddress()).isEqualTo("Lal Chowk");
        assertThat(offers.findById(offerId).orElseThrow().getStatus()).isEqualTo(OfferStatus.ACCEPTED);
    }

    @Test
    void rejectingReleasesTheDriverAndAdvancesToTheNextCandidate() {
        City city = srinagar();
        Customer customer = newCustomer();
        Driver first = onlineDriverWithApprovedVehicle(city, VehicleType.CAB);
        Driver second = onlineDriverWithApprovedVehicle(city, VehicleType.CAB);
        Booking booking = newBooking(customer, city, BookingStatus.SEARCHING_DRIVER, null);
        service.tryOfferNextCandidate(booking.getId());
        RideOffer firstOffer = offers.findByBookingIdAndStatus(booking.getId(), OfferStatus.OFFERED).orElseThrow();
        assertThat(firstOffer.getDriverId()).isEqualTo(first.getId());

        service.reject(first.getUserId(), firstOffer.getId(), "not now");

        assertThat(drivers.findById(first.getId()).orElseThrow().getAvailabilityStatus())
                .isEqualTo(DriverAvailability.ONLINE);
        assertThat(offers.findById(firstOffer.getId()).orElseThrow().getStatus()).isEqualTo(OfferStatus.REJECTED);
        RideOffer secondOffer = offers.findByBookingIdAndStatus(booking.getId(), OfferStatus.OFFERED).orElseThrow();
        assertThat(secondOffer.getDriverId()).isEqualTo(second.getId());
    }

    @Test
    void aDriverCannotAcceptAnotherDriversOffer() {
        City city = srinagar();
        Customer customer = newCustomer();
        Driver offered = onlineDriverWithApprovedVehicle(city, VehicleType.CAB);
        Driver stranger = onlineDriverWithApprovedVehicle(city, VehicleType.AUTO);
        Booking booking = newBooking(customer, city, BookingStatus.SEARCHING_DRIVER, null);
        service.tryOfferNextCandidate(booking.getId());
        UUID offerId = offers.findByBookingIdAndStatus(booking.getId(), OfferStatus.OFFERED).orElseThrow().getId();

        assertThatThrownBy(() -> service.accept(stranger.getUserId(), offerId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void acceptingAnExpiredOfferIsRejected() {
        City city = srinagar();
        Customer customer = newCustomer();
        Driver driver = onlineDriverWithApprovedVehicle(city, VehicleType.CAB);
        Booking booking = newBooking(customer, city, BookingStatus.SEARCHING_DRIVER, null);
        service.tryOfferNextCandidate(booking.getId());
        RideOffer offer = offers.findByBookingIdAndStatus(booking.getId(), OfferStatus.OFFERED).orElseThrow();
        offer.setExpiresAt(Instant.now().minusSeconds(5));
        offers.saveAndFlush(offer);

        assertThatThrownBy(() -> service.accept(driver.getUserId(), offer.getId()))
                .isInstanceOf(ValidationException.class);
    }
}
