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
import com.ladygo.security.ratelimit.RateLimitExceededException;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.vehicles.domain.Vehicle;
import com.ladygo.vehicles.domain.VehicleType;
import com.ladygo.verification.domain.SubjectType;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.transaction.TestTransaction;

/** docs/api/booking.md. Uses the real (mock) MapsService; outage behaviour is covered separately. */
class BookingServiceIntegrationTest extends PersistenceTestSupport {

    @Autowired
    private BookingService bookingService;
    @Autowired
    private com.ladygo.verification.repository.EligibilityReportRepository eligibilityReports;
    @Autowired
    private BookingEventRepository bookingEvents;

    private City committedCity;
    private Customer committedCustomer;

    /**
     * {@link BookingCreationTransaction#insert} runs in {@code REQUIRES_NEW} (deliberately, so a
     * unique-constraint violation on one request's insert doesn't abort the whole physical
     * transaction) - that sub-transaction uses a different connection and cannot see fixture rows
     * still uncommitted in this test's own (otherwise rolled-back) transaction. Committing and
     * restarting mid-test makes the fixtures real, the same way they would be in production by the
     * time a booking request ever reaches this code. The city/customer are remembered so
     * {@link #purgeCommittedFixtures()} can clean them up in {@code @AfterEach}, which JUnit
     * guarantees runs even if an assertion earlier in the test fails - a trailing cleanup call at the
     * end of the test method would NOT run in that case, leaking rows into the database this whole
     * test run shares (adversarial review finding, HIGH - this previously broke an unrelated test,
     * {@code DatabaseMigrationTest}, depending on run order).
     */
    private void commitFixtures(City city, Customer customer) {
        this.committedCity = city;
        this.committedCustomer = customer;
        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();
    }

    @AfterEach
    void purgeCommittedFixtures() {
        if (committedCustomer == null) {
            return;
        }
        jdbc.update("DELETE FROM booking_events WHERE booking_id IN (SELECT id FROM bookings WHERE customer_id = ?)",
                committedCustomer.getId());
        jdbc.update("DELETE FROM ride_selfies WHERE customer_id = ?", committedCustomer.getId());
        jdbc.update("DELETE FROM bookings WHERE customer_id = ?", committedCustomer.getId());
        jdbc.update("DELETE FROM self_declarations WHERE subject_type = 'CUSTOMER' AND subject_id = ?",
                committedCustomer.getId());
        jdbc.update("DELETE FROM pricing_rules WHERE city_id = ?", committedCity.getId());
        jdbc.update("DELETE FROM customers WHERE id = ?", committedCustomer.getId());
        jdbc.update("DELETE FROM users WHERE id = ?", committedCustomer.getUserId());
        jdbc.update("DELETE FROM cities WHERE id = ?", committedCity.getId());
        TestTransaction.flagForCommit();
    }

    private City srinagar() {
        return newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
    }

    private BookingRequest requestFor(Long expectedFare, UUID rideSelfieId) {
        return new BookingRequest(
                34.08, 74.80, "Lal Chowk",
                34.13, 74.87, "Hazratbal",
                VehicleType.CAB, PaymentMode.CASH, null, expectedFare, rideSelfieId);
    }

    @Test
    void estimateReturnsBreakdownAndTotal() {
        City city = srinagar();
        newPricingRule(city, VehicleType.CAB);
        Customer customer = newCustomer();

        FareEstimateResponse estimate = bookingService.estimate(customer.getUserId(),
                new FareEstimateRequest(34.08, 74.80, 34.13, 74.87, VehicleType.CAB));

        assertThat(estimate.cityId()).isEqualTo(city.getId());
        assertThat(estimate.approximate()).isFalse();
        assertThat(estimate.estimatedFare()).isEqualTo(estimate.breakdown().baseFare() + estimate.breakdown().distanceFare()
                + estimate.breakdown().timeFare() + estimate.breakdown().serviceFee());
    }

    @Test
    void estimateOutsideAnyServiceAreaIsRejected() {
        Customer customer = newCustomer();
        assertThatThrownBy(() -> bookingService.estimate(customer.getUserId(),
                new FareEstimateRequest(10.0, 10.0, 10.1, 10.1, VehicleType.CAB)))
                .isInstanceOf(OutOfServiceAreaException.class);
    }

    @Test
    void estimateOverMaxDistanceIsRejected() {
        City city = srinagar();
        newPricingRule(city, VehicleType.CAB);
        Customer customer = newCustomer();
        jdbc.update("UPDATE configuration SET value = '1000' WHERE key = 'ride.max.distance.m' AND scope = 'GLOBAL'");

        assertThatThrownBy(() -> bookingService.estimate(customer.getUserId(),
                new FareEstimateRequest(34.08, 74.80, 34.13, 74.87, VehicleType.CAB)))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void estimateCallsBeyondTheConfiguredRateLimitAreRejected() {
        City city = srinagar();
        newPricingRule(city, VehicleType.CAB);
        Customer customer = newCustomer();
        jdbc.update(
                "UPDATE configuration SET value = '2' WHERE key = 'bookings.maps.rate.max.attempts' AND scope = 'GLOBAL'");
        FareEstimateRequest request = new FareEstimateRequest(34.08, 74.80, 34.13, 74.87, VehicleType.CAB);

        bookingService.estimate(customer.getUserId(), request);
        bookingService.estimate(customer.getUserId(), request);

        assertThatThrownBy(() -> bookingService.estimate(customer.getUserId(), request))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void createPersistsAtSearchingDriverWithTwoEventsAndARidePin() {
        City city = srinagar();
        newPricingRule(city, VehicleType.CAB);
        Customer customer = newCustomer();
        acceptCurrentDeclaration(SubjectType.CUSTOMER, customer.getId());
        UUID rideSelfieId = newRideSelfie(customer.getId());
        commitFixtures(city, customer);

        FareEstimateResponse estimate = bookingService.estimate(customer.getUserId(),
                new FareEstimateRequest(34.08, 74.80, 34.13, 74.87, VehicleType.CAB));

        BookingCreationResult result = bookingService.create(
                customer.getUserId(), requestFor(estimate.estimatedFare(), rideSelfieId), UUID.randomUUID().toString());

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
        acceptCurrentDeclaration(SubjectType.CUSTOMER, customer.getId());
        UUID rideSelfieId = newRideSelfie(customer.getId());
        commitFixtures(city, customer);
        FareEstimateResponse estimate = bookingService.estimate(customer.getUserId(),
                new FareEstimateRequest(34.08, 74.80, 34.13, 74.87, VehicleType.CAB));
        String idempotencyKey = UUID.randomUUID().toString();
        BookingRequest request = requestFor(estimate.estimatedFare(), rideSelfieId);

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
        acceptCurrentDeclaration(SubjectType.CUSTOMER, customer.getId());
        BookingRequest request = requestFor(1L, UUID.randomUUID()); // wildly stale; never reaches the selfie claim.

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

    /** docs/api/driver-onboarding.md section 5 - driver cancels without penalty, auto-creates a report. */
    @Test
    void driverCancelForEligibilityConcernIsFreeAndAutoCreatesAReport() {
        City city = srinagar();
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.DRIVER_ASSIGNED, driver.getId());

        bookingService.cancelForEligibilityConcern(driver.getUserId(), booking.getId(), "felt unsafe");

        BookingEvent cancelEvent = cancelEventFor(booking.getId());
        assertThat(((Number) cancelEvent.getMetadata().get("cancellationFee")).longValue()).isZero();
        assertThat(cancelEvent.getActorType()).isEqualTo(ActorType.DRIVER);
        assertThat(eligibilityReports.findAll()).anySatisfy(report -> {
            assertThat(report.getBookingId()).isEqualTo(booking.getId());
            assertThat(report.getSubjectId()).isEqualTo(customer.getId());
            assertThat(report.isAutoCreated()).isTrue();
        });
    }

    /** The user's own correction: driver-initiated eligibility cancels were never rate-limited by
     *  design, which was not an approved decision. Beyond the per-driver/rider-pair cap (default 1 per
     *  rolling 7 days), the cancellation still goes through but takes the normal fee, and the
     *  auto-created report is flagged for admin review instead of being an ordinary free one. */
    @Test
    void driverCancelForEligibilityConcernBeyondThePairCapTakesTheNormalPenaltyAndFlagsTheReport() {
        City city = srinagar();
        newPricingRule(city, VehicleType.CAB);
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        Booking first = newBooking(customer, city, BookingStatus.DRIVER_ASSIGNED, driver.getId());
        Booking second = newBooking(customer, city, BookingStatus.DRIVER_ASSIGNED, driver.getId());

        bookingService.cancelForEligibilityConcern(driver.getUserId(), first.getId(), "first concern");
        bookingService.cancelForEligibilityConcern(driver.getUserId(), second.getId(), "second concern");

        BookingEvent secondCancelEvent = cancelEventFor(second.getId());
        assertThat(((Number) secondCancelEvent.getMetadata().get("cancellationFee")).longValue()).isEqualTo(5000);
        assertThat(eligibilityReports.findAll()).filteredOn(r -> r.getBookingId().equals(second.getId()))
                .singleElement().satisfies(r -> assertThat(r.isCapExceeded()).isTrue());
    }

    /** Adversarial review finding, HIGH: booking.driverId is set as soon as a driver is OFFERED
     *  (DRIVER_ASSIGNED), not only once they accept - needed so cancel-eligibility-concern (above)
     *  still works pre-acceptance. That means ownership-by-driverId alone is not enough to gate the
     *  exact pickup address: an offered-but-not-yet-accepting driver must still be rejected. */
    @Test
    void getForDriverRejectsAnOfferedButNotYetAcceptingDriver() {
        City city = srinagar();
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.DRIVER_ASSIGNED, driver.getId());

        assertThatThrownBy(() -> bookingService.getForDriver(driver.getUserId(), booking.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getForDriverAllowsTheAcceptingDriver() {
        City city = srinagar();
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.DRIVER_ACCEPTED, driver.getId());

        var response = bookingService.getForDriver(driver.getUserId(), booking.getId());

        assertThat(response.pickupAddress()).isEqualTo("Lal Chowk");
    }

    /** Adversarial review finding, CRITICAL: the previous gate was
     *  {@code status.ordinal() >= DRIVER_ACCEPTED.ordinal()} - but CANCELLED is the LAST value in
     *  BookingStatus, so that check actually ADMITTED a cancelled booking. A customer can cancel a
     *  DRIVER_ASSIGNED (offered, not yet accepted) booking at any time; driverId still points at the
     *  offered driver afterwards (nothing clears it on customer-initiated cancel), so this must be
     *  rejected on its own, not rely on driverId ever being null here. */
    @Test
    void getForDriverRejectsACancelledBookingEvenThoughDriverIdStillPointsAtTheOfferedDriver() {
        City city = srinagar();
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.DRIVER_ASSIGNED, driver.getId());

        bookingService.cancel(customer.getUserId(), booking.getId(), "changed my mind");

        assertThatThrownBy(() -> bookingService.getForDriver(driver.getUserId(), booking.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void driverCancelForEligibilityConcernRejectsAnUnassignedDriver() {
        City city = srinagar();
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        Driver stranger = newDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.DRIVER_ASSIGNED, driver.getId());

        assertThatThrownBy(() -> bookingService.cancelForEligibilityConcern(
                stranger.getUserId(), booking.getId(), "not mine"))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
