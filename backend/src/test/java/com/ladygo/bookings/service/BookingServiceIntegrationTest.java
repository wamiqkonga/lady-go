package com.ladygo.bookings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingEvent;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.bookings.repository.BookingEventRepository;
import com.ladygo.bookings.web.dto.BookingDetailResponse;
import com.ladygo.bookings.web.dto.BookingRequest;
import com.ladygo.bookings.web.dto.FareEstimateRequest;
import com.ladygo.bookings.web.dto.FareEstimateResponse;
import com.ladygo.common.api.PageResponse;
import com.ladygo.common.domain.ActorType;
import com.ladygo.common.exception.OutOfServiceAreaException;
import com.ladygo.common.exception.ResourceNotFoundException;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.customers.domain.Customer;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.locations.domain.City;
import com.ladygo.payments.domain.PaymentMode;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.vehicles.domain.Vehicle;
import com.ladygo.vehicles.domain.VehicleType;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.transaction.TestTransaction;

/** docs/api/booking.md. Uses the real (mock) MapsService; outage behaviour is covered separately. */
class BookingServiceIntegrationTest extends PersistenceTestSupport {

    @Autowired
    private BookingService bookingService;
    @Autowired
    private BookingEventRepository bookingEvents;

    /**
     * {@link BookingCreationTransaction#insert} runs in {@code REQUIRES_NEW} (deliberately, so a
     * unique-constraint violation on one request's insert doesn't abort the whole physical
     * transaction) - that sub-transaction uses a different connection and cannot see fixture rows
     * still uncommitted in this test's own (otherwise rolled-back) transaction. Committing and
     * restarting mid-test makes the fixtures real, the same way they would be in production by the
     * time a booking request ever reaches this code.
     */
    private void commitFixtures() {
        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();
    }

    private City srinagar() {
        return newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
    }

    private BookingRequest requestFor(Long expectedFare) {
        return new BookingRequest(
                34.08, 74.80, "Lal Chowk",
                34.13, 74.87, "Hazratbal",
                VehicleType.CAB, PaymentMode.CASH, null, expectedFare);
    }

    @Test
    void estimateReturnsBreakdownAndTotal() {
        City city = srinagar();
        newPricingRule(city, VehicleType.CAB);

        FareEstimateResponse estimate = bookingService.estimate(
                new FareEstimateRequest(34.08, 74.80, 34.13, 74.87, VehicleType.CAB));

        assertThat(estimate.cityId()).isEqualTo(city.getId());
        assertThat(estimate.approximate()).isFalse();
        assertThat(estimate.estimatedFare()).isEqualTo(estimate.breakdown().baseFare() + estimate.breakdown().distanceFare()
                + estimate.breakdown().timeFare() + estimate.breakdown().serviceFee());
    }

    @Test
    void estimateOutsideAnyServiceAreaIsRejected() {
        assertThatThrownBy(() -> bookingService.estimate(
                new FareEstimateRequest(10.0, 10.0, 10.1, 10.1, VehicleType.CAB)))
                .isInstanceOf(OutOfServiceAreaException.class);
    }

    @Test
    void estimateOverMaxDistanceIsRejected() {
        City city = srinagar();
        newPricingRule(city, VehicleType.CAB);
        jdbc.update("UPDATE configuration SET value = '1000' WHERE key = 'ride.max.distance.m' AND scope = 'GLOBAL'");

        assertThatThrownBy(() -> bookingService.estimate(
                new FareEstimateRequest(34.08, 74.80, 34.13, 74.87, VehicleType.CAB)))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void createPersistsAtSearchingDriverWithTwoEventsAndARidePin() {
        City city = srinagar();
        newPricingRule(city, VehicleType.CAB);
        Customer customer = newCustomer();
        commitFixtures();

        FareEstimateResponse estimate = bookingService.estimate(
                new FareEstimateRequest(34.08, 74.80, 34.13, 74.87, VehicleType.CAB));

        BookingCreationResult result = bookingService.create(
                customer.getUserId(), requestFor(estimate.estimatedFare()), UUID.randomUUID().toString());

        assertThat(result.created()).isTrue();
        BookingDetailResponse booking = result.booking();
        assertThat(booking.status()).isEqualTo(BookingStatus.SEARCHING_DRIVER);
        assertThat(booking.ridePin()).hasSize(4);
        assertThat(booking.driver()).isNull();

        var events = bookingEvents.findByBookingIdOrderByCreatedAtAsc(booking.id());
        assertThat(events).hasSize(2);
        assertThat(events.get(0).getFromStatus()).isNull();
        assertThat(events.get(0).getToStatus()).isEqualTo(BookingStatus.REQUESTED);
        assertThat(events.get(1).getToStatus()).isEqualTo(BookingStatus.SEARCHING_DRIVER);
        assertThat(events.get(1).getActorType()).isEqualTo(ActorType.SYSTEM);
    }

    @Test
    void repeatingTheSameIdempotencyKeyReplaysTheOriginalBooking() {
        City city = srinagar();
        newPricingRule(city, VehicleType.CAB);
        Customer customer = newCustomer();
        commitFixtures();
        FareEstimateResponse estimate = bookingService.estimate(
                new FareEstimateRequest(34.08, 74.80, 34.13, 74.87, VehicleType.CAB));
        String idempotencyKey = UUID.randomUUID().toString();
        BookingRequest request = requestFor(estimate.estimatedFare());

        BookingCreationResult first = bookingService.create(customer.getUserId(), request, idempotencyKey);
        BookingCreationResult second = bookingService.create(customer.getUserId(), request, idempotencyKey);

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.booking().id()).isEqualTo(first.booking().id());
        assertThat(bookingEvents.findByBookingIdOrderByCreatedAtAsc(first.booking().id())).hasSize(2);
    }

    @Test
    void aStaleExpectedFareBeyondToleranceIsRejectedWithTheFreshQuote() {
        City city = srinagar();
        newPricingRule(city, VehicleType.CAB);
        Customer customer = newCustomer();
        BookingRequest request = requestFor(1L); // wildly stale.

        assertThatThrownBy(() -> bookingService.create(customer.getUserId(), request, UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(FareChangedException.class,
                        ex -> assertThat(ex.getFreshEstimate().estimatedFare()).isGreaterThan(1L));
    }

    @Test
    void getRejectsAnotherCustomersBookingAsNotFound() {
        City city = srinagar();
        Customer owner = newCustomer();
        Customer stranger = newCustomer();
        Booking booking = newBooking(owner, city, BookingStatus.SEARCHING_DRIVER, null);

        assertThatThrownBy(() -> bookingService.get(stranger.getUserId(), booking.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void ridePinDisappearsAndDriverAppearsOnceADriverIsAssigned() {
        City city = srinagar();
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        Vehicle vehicle = newVehicle(driver);
        Booking booking = newBooking(customer, city, BookingStatus.DRIVER_ASSIGNED, driver.getId());

        BookingDetailResponse detail = bookingService.get(customer.getUserId(), booking.getId());
        assertThat(detail.ridePin()).hasSize(4);
        assertThat(detail.driver()).isNotNull();
        assertThat(detail.driver().vehicleRegistration()).isEqualTo(vehicle.getRegistrationNumber());

        Booking rideStarted = newBooking(customer, city, BookingStatus.RIDE_STARTED, driver.getId());
        BookingDetailResponse afterStart = bookingService.get(customer.getUserId(), rideStarted.getId());
        assertThat(afterStart.ridePin()).isNull();
    }

    @Test
    void cancellingBeforeADriverIsAssignedIsAlwaysFree() {
        City city = srinagar();
        Customer customer = newCustomer();
        Booking booking = newBooking(customer, city, BookingStatus.SEARCHING_DRIVER, null);

        bookingService.cancel(customer.getUserId(), booking.getId(), "changed my mind");

        BookingEvent cancelEvent = cancelEventFor(booking.getId());
        assertThat(cancelEvent.getMetadata()).containsEntry("cancellationFee", 0);
    }

    @Test
    void cancellingWithinTheGraceWindowAfterDriverAcceptedIsFree() {
        City city = srinagar();
        newPricingRule(city, VehicleType.CAB);
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.DRIVER_ASSIGNED, driver.getId());
        stateMachineAccept(booking);

        bookingService.cancel(customer.getUserId(), booking.getId(), "driver too slow");

        BookingEvent cancelEvent = cancelEventFor(booking.getId());
        assertThat(((Number) cancelEvent.getMetadata().get("cancellationFee")).longValue()).isZero();
    }

    @Test
    void cancellingAfterTheGraceWindowChargesTheActiveCancellationFee() {
        City city = srinagar();
        newPricingRule(city, VehicleType.CAB);
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.DRIVER_ASSIGNED, driver.getId());
        insertDriverAcceptedEventAt(booking, Instant.now().minusSeconds(600));

        bookingService.cancel(customer.getUserId(), booking.getId(), "driver too slow");

        BookingEvent cancelEvent = cancelEventFor(booking.getId());
        assertThat(((Number) cancelEvent.getMetadata().get("cancellationFee")).longValue()).isEqualTo(5000L);
    }

    @Test
    void historyIsPaginatedNewestFirstAndOmitsRidePinAndDriver() {
        City city = srinagar();
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        newBooking(customer, city, BookingStatus.SEARCHING_DRIVER, null);
        newBooking(customer, city, BookingStatus.SEARCHING_DRIVER, null);
        newBooking(customer, city, BookingStatus.DRIVER_ASSIGNED, driver.getId());

        PageResponse<BookingDetailResponse> page = bookingService.history(customer.getUserId(), PageRequest.of(0, 2));

        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.content()).hasSize(2);
        assertThat(page.content()).allSatisfy(item -> {
            assertThat(item.ridePin()).isNull();
            assertThat(item.driver()).isNull();
        });
    }

    /** Records the historical DRIVER_ACCEPTED event the grace-window check reads, without needing to run the
     *  whole state machine (the booking's own current status is irrelevant to that check). */
    private void stateMachineAccept(Booking booking) {
        insertDriverAcceptedEventAt(booking, Instant.now());
    }

    /** booking_events is append-only (DB trigger) so a backdated timestamp must be inserted directly -
     *  {@code @CreationTimestamp} always stamps "now" and has no setter to override through JPA. */
    private void insertDriverAcceptedEventAt(Booking booking, Instant createdAt) {
        jdbc.update(
                "INSERT INTO booking_events (id, booking_id, from_status, to_status, actor_type, actor_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), booking.getId(), BookingStatus.DRIVER_ASSIGNED.name(),
                BookingStatus.DRIVER_ACCEPTED.name(), ActorType.DRIVER.name(), booking.getDriverId(),
                Timestamp.from(createdAt));
    }

    private BookingEvent cancelEventFor(UUID bookingId) {
        return bookingEvents.findByBookingIdOrderByCreatedAtAsc(bookingId).stream()
                .filter(e -> e.getToStatus() == BookingStatus.CANCELLED)
                .findFirst()
                .orElseThrow();
    }
}
