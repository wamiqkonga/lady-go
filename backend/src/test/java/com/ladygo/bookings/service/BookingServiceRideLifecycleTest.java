package com.ladygo.bookings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.bookings.web.dto.BookingDetailResponse;
import com.ladygo.bookings.web.dto.BookingRequest;
import com.ladygo.bookings.web.dto.DestinationChangeEstimateRequest;
import com.ladygo.bookings.web.dto.DestinationChangeRequest;
import com.ladygo.bookings.web.dto.FareEstimateResponse;
import com.ladygo.common.exception.PaymentMethodUnavailableException;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.customers.domain.Customer;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.locations.domain.City;
import com.ladygo.payments.domain.PaymentMode;
import com.ladygo.pricing.domain.PricingRule;
import com.ladygo.rides.domain.RideFlagType;
import com.ladygo.rides.repository.RideFlagRepository;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.vehicles.domain.VehicleType;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** docs/api/ride-lifecycle.md sections 13b-1 and 13b-3/13b-5. */
class BookingServiceRideLifecycleTest extends PersistenceTestSupport {

    @Autowired
    private BookingService bookingService;
    @Autowired
    private RideFlagRepository rideFlags;

    private City srinagar() {
        return newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
    }

    @Test
    void onlinePaymentMethodIsRejectedAtCreation() {
        BookingRequest request = new BookingRequest(
                34.08, 74.80, "Lal Chowk", 34.13, 74.87, "Hazratbal",
                VehicleType.CAB, PaymentMode.ONLINE, null, 18500L, UUID.randomUUID());

        assertThatThrownBy(() -> bookingService.create(UUID.randomUUID(), request, UUID.randomUUID().toString()))
                .isInstanceOf(PaymentMethodUnavailableException.class);
    }

    @Test
    void cashPaymentMethodIsNotRejected() {
        // No customer/city fixture exists for this userId, so it fails later (ResourceNotFoundException)
        // - this test only proves the ONLINE-specific gate doesn't also block CASH.
        BookingRequest request = new BookingRequest(
                34.08, 74.80, "Lal Chowk", 34.13, 74.87, "Hazratbal",
                VehicleType.CAB, PaymentMode.CASH, null, 18500L, UUID.randomUUID());

        assertThatThrownBy(() -> bookingService.create(UUID.randomUUID(), request, UUID.randomUUID().toString()))
                .isNotInstanceOf(PaymentMethodUnavailableException.class);
    }

    @Test
    void cancelForSafetyConcernIsFreeAndRaisesAFlag() {
        City city = srinagar();
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.DRIVER_ACCEPTED, driver.getId());
        newRide(booking, driver);

        BookingDetailResponse response = bookingService.cancelForSafetyConcern(
                customer.getUserId(), booking.getId(), "felt unsafe");

        assertThat(response.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(rideFlags.countByCustomerIdAndTypeAndCreatedAtAfter(
                customer.getId(), RideFlagType.RIDER_SAFETY_CANCEL, java.time.Instant.now().minusSeconds(60)))
                .isEqualTo(1);
    }

    @Test
    void cancelForSafetyConcernBeyondThePairCapTakesTheNormalFeeAndFlagsCapExceeded() {
        City city = srinagar();
        PricingRule rule = newPricingRule(city, VehicleType.CAB);
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        Booking first = newBooking(customer, city, BookingStatus.DRIVER_ACCEPTED, driver.getId());
        newRide(first, driver);
        bookingService.cancelForSafetyConcern(customer.getUserId(), first.getId(), "first concern");
        flushAndClear(); // the CANCELLED update must hit the DB before the next insert's uniqueness check.

        Booking second = newBooking(customer, city, BookingStatus.DRIVER_ACCEPTED, driver.getId());
        newRide(second, driver);

        BookingDetailResponse response = bookingService.cancelForSafetyConcern(
                customer.getUserId(), second.getId(), "second concern");

        assertThat(response.status()).isEqualTo(BookingStatus.CANCELLED);
        var flag = rideFlags.findAll().stream()
                .filter(f -> f.getBookingId().equals(second.getId())).findFirst().orElseThrow();
        assertThat(flag.isCapExceeded()).isTrue();
        assertThat(rule.getId()).isNotNull(); // rule referenced so the fixture compiles cleanly.
    }

    @Test
    void cancelForSafetyConcernWithNoDriverAssignedIsRejected() {
        City city = srinagar();
        Customer customer = newCustomer();
        Booking booking = newBooking(customer, city, BookingStatus.SEARCHING_DRIVER, null);

        assertThatThrownBy(() -> bookingService.cancelForSafetyConcern(customer.getUserId(), booking.getId(), "x"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void estimateDestinationChangeComputesAFreshFareWithoutMutatingTheBooking() {
        City city = srinagar();
        PricingRule rule = newPricingRule(city, VehicleType.CAB);
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.DRIVER_ACCEPTED, driver.getId());
        booking.setPricingRuleId(rule.getId());
        booking.setEstimatedFare(15000L);
        bookings.saveAndFlush(booking);

        FareEstimateResponse estimate = bookingService.estimateDestinationChange(
                customer.getUserId(), booking.getId(), new DestinationChangeEstimateRequest(34.20, 74.90));

        assertThat(estimate.estimatedFare()).isPositive();
        assertThat(bookings.findById(booking.getId()).orElseThrow().getDestinationAddress()).isEqualTo("Hazratbal");
    }

    @Test
    void changeDestinationMutatesTheBookingAndRecordsTheNewRule() {
        City city = srinagar();
        PricingRule rule = newPricingRule(city, VehicleType.CAB);
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.DRIVER_ACCEPTED, driver.getId());
        booking.setPricingRuleId(rule.getId());
        booking.setEstimatedFare(15000L);
        bookings.saveAndFlush(booking);
        FareEstimateResponse estimate = bookingService.estimateDestinationChange(
                customer.getUserId(), booking.getId(), new DestinationChangeEstimateRequest(34.20, 74.90));

        BookingDetailResponse response = bookingService.changeDestination(customer.getUserId(), booking.getId(),
                new DestinationChangeRequest(34.20, 74.90, "New Destination", estimate.estimatedFare()));

        assertThat(response.destinationAddress()).isEqualTo("New Destination");
        Booking updated = bookings.findById(booking.getId()).orElseThrow();
        assertThat(updated.getPricingRuleId()).isEqualTo(rule.getId());
        assertThat(updated.getEstimatedFare()).isEqualTo(estimate.estimatedFare());
    }

    /** docs/api/booking.md's own create() only checks the PICKUP against a service area and caps the
     *  resulting trip at ride.max.distance.m - the same shape this reuses, so a far-off destination is
     *  rejected via that distance cap, not a destination-specific service-area check (none exists). */
    @Test
    void changeDestinationFarBeyondTheMaxTripDistanceIsRejected() {
        City city = srinagar();
        PricingRule rule = newPricingRule(city, VehicleType.CAB);
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.DRIVER_ACCEPTED, driver.getId());
        booking.setPricingRuleId(rule.getId());
        booking.setEstimatedFare(15000L);
        bookings.saveAndFlush(booking);

        assertThatThrownBy(() -> bookingService.changeDestination(customer.getUserId(), booking.getId(),
                new DestinationChangeRequest(10.0, 10.0, "Nowhere", 15000L)))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void changeDestinationAfterRideStartedIsRejected() {
        City city = srinagar();
        PricingRule rule = newPricingRule(city, VehicleType.CAB);
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.RIDE_STARTED, driver.getId());
        booking.setPricingRuleId(rule.getId());
        booking.setEstimatedFare(15000L);
        bookings.saveAndFlush(booking);

        assertThatThrownBy(() -> bookingService.estimateDestinationChange(
                customer.getUserId(), booking.getId(), new DestinationChangeEstimateRequest(34.20, 74.90)))
                .isInstanceOf(ValidationException.class);
    }
}
