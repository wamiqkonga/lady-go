package com.ladygo.rides.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.bookings.service.RidePinService;
import com.ladygo.bookings.web.dto.BookingDetailResponse;
import com.ladygo.common.exception.BookingStateException;
import com.ladygo.common.exception.ResourceNotFoundException;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.customers.domain.Customer;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.domain.DriverVerificationStatus;
import com.ladygo.locations.domain.City;
import com.ladygo.pricing.domain.PricingRule;
import com.ladygo.rides.domain.Ride;
import com.ladygo.rides.domain.RideFlag;
import com.ladygo.rides.domain.RideFlagRiderResponse;
import com.ladygo.rides.domain.RideFlagType;
import com.ladygo.rides.domain.RideStatus;
import com.ladygo.rides.repository.RideFlagRepository;
import com.ladygo.security.ratelimit.RateLimitExceededException;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.vehicles.domain.VehicleType;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.transaction.TestTransaction;

/** docs/api/ride-lifecycle.md. */
class RideLifecycleServiceTest extends PersistenceTestSupport {

    @Autowired
    private RideLifecycleService service;
    @Autowired
    private RidePinService ridePinService;
    @Autowired
    private RideFlagRepository rideFlags;
    @Autowired
    private com.ladygo.support.repository.SafetyIncidentRepository safetyIncidents;

    private UUID committedCityId;
    private UUID committedCustomerUserId;
    private UUID committedDriverUserId;

    /** Only used by {@link #theDurableLockActuallyPersistsAcrossARealCommitNotJustWithinTheTestsOwnTransaction()}
     *  below, which commits for real instead of relying on this class's normal test-transaction
     *  auto-rollback - every other test leaves this null and this is a no-op for them. Same shape as
     *  CrossPhaseJourneyTest's purgeCommittedFixtures. */
    @AfterEach
    void purgeCommittedFixture() {
        if (committedCustomerUserId == null) {
            return;
        }
        jdbc.update("DELETE FROM ride_flags WHERE customer_id IN (SELECT id FROM customers WHERE user_id = ?)",
                committedCustomerUserId);
        jdbc.update("DELETE FROM rides WHERE customer_id IN (SELECT id FROM customers WHERE user_id = ?)",
                committedCustomerUserId);
        jdbc.update("DELETE FROM booking_events WHERE booking_id IN "
                + "(SELECT b.id FROM bookings b JOIN customers c ON c.id = b.customer_id WHERE c.user_id = ?)",
                committedCustomerUserId);
        jdbc.update("DELETE FROM bookings WHERE customer_id IN (SELECT id FROM customers WHERE user_id = ?)",
                committedCustomerUserId);
        jdbc.update("DELETE FROM pricing_rules WHERE city_id = ?", committedCityId);
        jdbc.update("DELETE FROM drivers WHERE user_id = ?", committedDriverUserId);
        jdbc.update("DELETE FROM customers WHERE user_id = ?", committedCustomerUserId);
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", committedCustomerUserId, committedDriverUserId);
        jdbc.update("DELETE FROM cities WHERE id = ?", committedCityId);
        TestTransaction.flagForCommit();
    }

    private Driver driverAcceptedFor(City city) {
        Driver driver = newDriver(city);
        driver.setVerificationStatus(DriverVerificationStatus.APPROVED);
        driver.setAvailabilityStatus(com.ladygo.drivers.domain.DriverAvailability.BUSY);
        driver.setCurrentLocation(point(74.80, 34.08)); // matches newBooking's fixed pickup.
        driver.setCurrentLocationAt(Instant.now());
        driver.setCurrentLocationAccuracyM(5.0);
        drivers.saveAndFlush(driver);
        return driver;
    }

    private Booking acceptedBooking(Customer customer, City city, Driver driver, PricingRule rule) {
        Booking booking = newBooking(customer, city, BookingStatus.DRIVER_ACCEPTED, driver.getId());
        booking.setPricingRuleId(rule.getId());
        booking.setEstimatedFare(15000L);
        bookings.saveAndFlush(booking);
        newRide(booking, driver);
        return booking;
    }

    private Booking rideStartedBooking(Customer customer, City city, Driver driver, PricingRule rule) {
        Booking booking = acceptedBooking(customer, city, driver, rule);
        booking.setStatus(BookingStatus.RIDE_STARTED);
        bookings.saveAndFlush(booking);
        Ride ride = rides.findByBookingId(booking.getId()).orElseThrow();
        ride.setStatus(RideStatus.STARTED);
        ride.setStartedAt(Instant.now().minusSeconds(60));
        ride.setStartLocation(point(74.80, 34.08));
        rides.saveAndFlush(ride);
        return booking;
    }

    @Test
    void markArrivingTransitionsAndSetsTheArrivingTimestamp() {
        City city = newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
        Customer customer = newCustomer();
        Driver driver = driverAcceptedFor(city);
        PricingRule rule = newPricingRule(city, VehicleType.CAB);
        Booking booking = acceptedBooking(customer, city, driver, rule);

        BookingDetailResponse response = service.markArriving(driver.getUserId(), booking.getId());

        assertThat(response.status()).isEqualTo(BookingStatus.DRIVER_ARRIVING);
        assertThat(bookings.findById(booking.getId()).orElseThrow().getDriverArrivingAt()).isNotNull();
    }

    @Test
    void markArrivingIsIdempotent() {
        City city = newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
        Customer customer = newCustomer();
        Driver driver = driverAcceptedFor(city);
        PricingRule rule = newPricingRule(city, VehicleType.CAB);
        Booking booking = acceptedBooking(customer, city, driver, rule);

        service.markArriving(driver.getUserId(), booking.getId());
        BookingDetailResponse second = service.markArriving(driver.getUserId(), booking.getId());

        assertThat(second.status()).isEqualTo(BookingStatus.DRIVER_ARRIVING);
    }

    @Test
    void markArrivedPassesWhenWithinRadiusAndFresh() {
        City city = newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
        Customer customer = newCustomer();
        Driver driver = driverAcceptedFor(city);
        PricingRule rule = newPricingRule(city, VehicleType.CAB);
        Booking booking = acceptedBooking(customer, city, driver, rule);
        service.markArriving(driver.getUserId(), booking.getId());

        BookingDetailResponse response = service.markArrived(driver.getUserId(), booking.getId());

        assertThat(response.status()).isEqualTo(BookingStatus.DRIVER_ARRIVED);
    }

    @Test
    void markArrivedFailsClosedWhenTooFarFromPickup() {
        City city = newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
        Customer customer = newCustomer();
        Driver driver = driverAcceptedFor(city);
        driver.setCurrentLocation(point(74.10, 34.40)); // far from the fixed (74.80, 34.08) pickup.
        drivers.saveAndFlush(driver);
        PricingRule rule = newPricingRule(city, VehicleType.CAB);
        Booking booking = acceptedBooking(customer, city, driver, rule);
        service.markArriving(driver.getUserId(), booking.getId());

        assertThatThrownBy(() -> service.markArrived(driver.getUserId(), booking.getId()))
                .isInstanceOf(ValidationException.class);
        assertThat(bookings.findById(booking.getId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.DRIVER_ARRIVING);
    }

    @Test
    void markArrivedFailsClosedWhenLocationIsStale() {
        City city = newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
        Customer customer = newCustomer();
        Driver driver = driverAcceptedFor(city);
        driver.setCurrentLocationAt(Instant.now().minus(5, ChronoUnit.MINUTES));
        drivers.saveAndFlush(driver);
        PricingRule rule = newPricingRule(city, VehicleType.CAB);
        Booking booking = acceptedBooking(customer, city, driver, rule);
        service.markArriving(driver.getUserId(), booking.getId());

        assertThatThrownBy(() -> service.markArrived(driver.getUserId(), booking.getId()))
                .isInstanceOf(ValidationException.class);
    }

    private Booking arrivedBooking(Customer customer, City city, Driver driver, PricingRule rule) {
        Booking booking = acceptedBooking(customer, city, driver, rule);
        booking.setStatus(BookingStatus.DRIVER_ARRIVED);
        bookings.saveAndFlush(booking);
        return booking;
    }

    @Test
    void startRideWithTheCorrectPinStartsTheRideAndTheRideRow() {
        City city = newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
        Customer customer = newCustomer();
        Driver driver = driverAcceptedFor(city);
        PricingRule rule = newPricingRule(city, VehicleType.CAB);
        Booking booking = arrivedBooking(customer, city, driver, rule);
        String pin = ridePinService.derive(booking.getId());

        BookingDetailResponse response = service.startRide(driver.getUserId(), booking.getId(), pin);

        assertThat(response.status()).isEqualTo(BookingStatus.RIDE_STARTED);
        Ride ride = rides.findByBookingId(booking.getId()).orElseThrow();
        assertThat(ride.getStatus()).isEqualTo(RideStatus.STARTED);
        assertThat(ride.getStartedAt()).isNotNull();
        assertThat(ride.getStartLocation()).isNotNull();
    }

    @Test
    void startRideWithAWrongPinIsRejectedAndDoesNotStartTheRide() {
        City city = newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
        Customer customer = newCustomer();
        Driver driver = driverAcceptedFor(city);
        PricingRule rule = newPricingRule(city, VehicleType.CAB);
        Booking booking = arrivedBooking(customer, city, driver, rule);

        assertThatThrownBy(() -> service.startRide(driver.getUserId(), booking.getId(), "0000"))
                .isInstanceOf(ValidationException.class);
        assertThat(bookings.findById(booking.getId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.DRIVER_ARRIVED);
    }

    @Test
    void startRideFailsTheGateEvenWithACorrectPinWhenTooFarFromPickup() {
        City city = newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
        Customer customer = newCustomer();
        Driver driver = driverAcceptedFor(city);
        driver.setCurrentLocation(point(74.10, 34.40));
        drivers.saveAndFlush(driver);
        PricingRule rule = newPricingRule(city, VehicleType.CAB);
        Booking booking = arrivedBooking(customer, city, driver, rule);
        String pin = ridePinService.derive(booking.getId());

        assertThatThrownBy(() -> service.startRide(driver.getUserId(), booking.getId(), pin))
                .isInstanceOf(ValidationException.class);
        assertThat(bookings.findById(booking.getId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.DRIVER_ARRIVED);
    }

    /** docs/api/ride-lifecycle.md section 5: the per-ride rate-limiter's own cap (default 5 attempts)
     *  upgrades into a durable lock, independent of its rolling window, and raises a flag. */
    @Test
    void repeatedWrongPinsLockTheRideDurablyAndRaiseAFlag() {
        City city = newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
        Customer customer = newCustomer();
        Driver driver = driverAcceptedFor(city);
        PricingRule rule = newPricingRule(city, VehicleType.CAB);
        Booking booking = arrivedBooking(customer, city, driver, rule);

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.startRide(driver.getUserId(), booking.getId(), "0000"))
                    .isInstanceOf(ValidationException.class);
        }
        assertThatThrownBy(() -> service.startRide(driver.getUserId(), booking.getId(), "0000"))
                .isInstanceOf(RateLimitExceededException.class);

        Booking locked = bookings.findById(booking.getId()).orElseThrow();
        assertThat(locked.getRidePinLockedAt()).isNotNull();
        List<RideFlag> flags = rideFlags.findByBookingIdAndType(booking.getId(), RideFlagType.PIN_LOCKOUT);
        assertThat(flags).hasSize(1);

        String correctPin = ridePinService.derive(booking.getId());
        assertThatThrownBy(() -> service.startRide(driver.getUserId(), booking.getId(), correctPin))
                .isInstanceOf(ValidationException.class);
    }

    /** Security review finding, MEDIUM: startRide's @Transactional boundary would otherwise roll back
     *  the durable PIN lock (and the per-ride rate limiter's own attempt counter, on EVERY rejected
     *  attempt, not just the final one) the instant it rethrows - but every other test in this class
     *  runs inside the test harness's own never-committed transaction, where nested @Transactional
     *  calls share that same transaction, so a rollback-and-rethrow has no visible effect until the
     *  whole test ends anyway. That's exactly what hid this bug: {@code
     *  repeatedWrongPinsLockTheRideDurablyAndRaiseAFlag} above still passes with or without the fix.
     *  This test forces each startRide call into its OWN independently-committing transaction, the way
     *  a real HTTP request actually behaves, to prove the lock survives for real. */
    @Test
    void theDurableLockActuallyPersistsAcrossARealCommitNotJustWithinTheTestsOwnTransaction() {
        City city = newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
        Customer customer = newCustomer();
        Driver driver = driverAcceptedFor(city);
        PricingRule rule = newPricingRule(city, VehicleType.CAB);
        Booking booking = arrivedBooking(customer, city, driver, rule);
        committedCityId = city.getId();
        committedCustomerUserId = customer.getUserId();
        committedDriverUserId = driver.getUserId();
        TestTransaction.flagForCommit();
        TestTransaction.end(); // the setup above is now a real, committed fact - not just session-visible.

        for (int i = 0; i < 6; i++) {
            TestTransaction.start();
            try {
                service.startRide(driver.getUserId(), booking.getId(), "0000");
            } catch (RuntimeException expected) {
                // every one of the 6 calls rejects - either "incorrect PIN" or, on the 6th, the
                // rate-limiter's own cap. What matters is whether the lock survives the commit below.
            }
            TestTransaction.flagForCommit();
            TestTransaction.end(); // commits (or would roll back, pre-fix) for real, independently.
        }

        TestTransaction.start();
        Booking reloaded = bookings.findById(booking.getId()).orElseThrow();
        assertThat(reloaded.getRidePinLockedAt()).isNotNull();
        assertThat(rideFlags.findByBookingIdAndType(booking.getId(), RideFlagType.PIN_LOCKOUT)).hasSize(1);
    }

    @Test
    void completeRideUsesTheEstimatedFareAndAdvancesToPaymentPending() {
        City city = newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
        Customer customer = newCustomer();
        Driver driver = driverAcceptedFor(city);
        PricingRule rule = newPricingRule(city, VehicleType.CAB);
        Booking booking = rideStartedBooking(customer, city, driver, rule);

        BookingDetailResponse response = service.completeRide(driver.getUserId(), booking.getId());

        assertThat(response.status()).isEqualTo(BookingStatus.PAYMENT_PENDING);
        Booking completed = bookings.findById(booking.getId()).orElseThrow();
        assertThat(completed.getFinalFare()).isEqualTo(15000L);
        Ride ride = rides.findByBookingId(booking.getId()).orElseThrow();
        assertThat(ride.getStatus()).isEqualTo(RideStatus.COMPLETED);
        assertThat(ride.getCompletedAt()).isNotNull();
    }

    @Test
    void completingFarFromTheDestinationRaisesASoftFlagButStillCompletes() {
        City city = newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
        Customer customer = newCustomer();
        Driver driver = driverAcceptedFor(city); // still at the pickup point, far from destination.
        PricingRule rule = newPricingRule(city, VehicleType.CAB);
        Booking booking = rideStartedBooking(customer, city, driver, rule);

        BookingDetailResponse response = service.completeRide(driver.getUserId(), booking.getId());

        assertThat(response.status()).isEqualTo(BookingStatus.PAYMENT_PENDING); // never blocked.
        List<RideFlag> flags = rideFlags.findByBookingIdAndType(booking.getId(), RideFlagType.COMPLETION_FAR_FROM_DESTINATION);
        assertThat(flags).hasSize(1);
    }

    /** Security review finding, CRITICAL: ending a ride the instant it starts used to float the fare
     *  down to the pricing rule's bare minimum (zero actual distance/duration), a free way to pay far
     *  less than the agreed estimate on any ride. Below the configured elapsed-time/distance
     *  threshold, the full estimated fare is charged instead - same as a normal completion. */
    /** docs/api/ride-lifecycle.md section 13c: a driver-initiated no-progress early end is always
     *  fee-waived, unconditionally - the rider pays nothing, not the full estimate (an earlier version
     *  of this fix charged the full estimate here, which your explicit correction replaced with this
     *  cancellation-fee-style policy). This is one of the three regressions you asked for by name
     *  ("driver ends with no progress"). */
    @Test
    void driverEndingTheRideImmediatelyWithNoProgressWaivesTheFareEntirely() {
        City city = newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
        Customer customer = newCustomer();
        Driver driver = driverAcceptedFor(city);
        PricingRule rule = newPricingRule(city, VehicleType.CAB);
        Booking booking = rideStartedBooking(customer, city, driver, rule);
        Ride ride = rides.findByBookingId(booking.getId()).orElseThrow();
        ride.setStartedAt(Instant.now()); // started just now - zero progress.
        rides.saveAndFlush(ride);

        BookingDetailResponse response = service.endRideEarlyAsDriver(driver.getUserId(), booking.getId(), "rider asked to stop");

        assertThat(response.status()).isEqualTo(BookingStatus.PAYMENT_PENDING);
        Booking ended = bookings.findById(booking.getId()).orElseThrow();
        assertThat(ended.getFinalFare()).isZero(); // waived, not the 15000 estimate, not a fee.
        List<RideFlag> flags = rideFlags.findByBookingIdAndType(booking.getId(), RideFlagType.EARLY_END);
        assertThat(flags).hasSize(1);
        assertThat(flags.get(0).getRaisedBy()).isEqualTo(com.ladygo.common.domain.ActorType.DRIVER);
    }

    /** Security review finding: isGenuinelyEarly was originally OR, not AND - elapsed time alone
     *  (parked, doing nothing, zero actual distance) satisfied it, so idling for ~200s and then
     *  ending early still got treated as "genuine progress" instead of "no progress". Fixed to AND:
     *  time elapsing for free must never be enough on its own; real road distance is also required.
     *  Proven here via the driver-waived outcome still applying (the no-progress branch, not the
     *  distance-based recompute branch, is what actually ran). */
    @Test
    void endingRideEarlyAfterOnlyElapsedTimeWithNoActualDistanceIsStillNoProgress() {
        City city = newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
        Customer customer = newCustomer();
        Driver driver = driverAcceptedFor(city);
        PricingRule rule = newPricingRule(city, VehicleType.CAB);
        Booking booking = rideStartedBooking(customer, city, driver, rule);
        Ride ride = rides.findByBookingId(booking.getId()).orElseThrow();
        ride.setStartedAt(Instant.now().minusSeconds(200)); // past the 120s threshold, but zero distance.
        rides.saveAndFlush(ride);

        BookingDetailResponse response = service.endRideEarlyAsDriver(driver.getUserId(), booking.getId(), "rider asked to stop");

        assertThat(response.status()).isEqualTo(BookingStatus.PAYMENT_PENDING);
        Booking ended = bookings.findById(booking.getId()).orElseThrow();
        // Driver-waived (no-progress branch) - if the OR bug were reintroduced, this would instead hit
        // the distance-based recompute branch and NOT be zero (it would floor to a nonzero minimum).
        assertThat(ended.getFinalFare()).isZero();
    }

    /** The legitimate case the AND must not break: real road distance AND real elapsed time both
     *  present still gets the distance-based recompute, floored at the pricing rule's minimum fare
     *  same as before this fix. */
    @Test
    void endingRideEarlyAfterGenuineProgressStillRecomputesTheFareWithTheMinimumFloor() {
        City city = newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
        Customer customer = newCustomer();
        Driver driver = driverAcceptedFor(city);
        PricingRule rule = newPricingRule(city, VehicleType.CAB); // minimumFare = 6000 (test helper default).
        Booking booking = rideStartedBooking(customer, city, driver, rule);
        Ride ride = rides.findByBookingId(booking.getId()).orElseThrow();
        ride.setStartedAt(Instant.now().minusSeconds(200)); // past the 120s min-elapsed threshold.
        rides.saveAndFlush(ride);
        // Two samples ~600m apart (past the 500m threshold), as DriverLocationService would have
        // written them during the ride.
        Instant t0 = Instant.now().minusSeconds(150);
        jdbc.update("INSERT INTO location_history (ride_id, driver_id, location, recorded_at) "
                + "VALUES (?, ?, CAST(ST_SetSRID(ST_MakePoint(?, ?), 4326) AS geography), ?)",
                ride.getId(), driver.getId(), 74.80, 34.08, java.sql.Timestamp.from(t0));
        jdbc.update("INSERT INTO location_history (ride_id, driver_id, location, recorded_at) "
                + "VALUES (?, ?, CAST(ST_SetSRID(ST_MakePoint(?, ?), 4326) AS geography), ?)",
                ride.getId(), driver.getId(), 74.806, 34.08, java.sql.Timestamp.from(t0.plusSeconds(60)));

        BookingDetailResponse response = service.endRideEarlyAsDriver(driver.getUserId(), booking.getId(), "rider asked to stop");

        assertThat(response.status()).isEqualTo(BookingStatus.PAYMENT_PENDING);
        Booking ended = bookings.findById(booking.getId()).orElseThrow();
        // ~554m / ~200s -> a genuine distance+time recompute (5000 base + ~665 distance + ~667 time =
        // ~6332 subtotal, above the 6000 minimum so the floor doesn't even need to apply here) plus the
        // rule's 5% service fee - far less than the 15000 estimate, which is the whole point: real
        // progress earns a real discount, unlike the zero-distance idle-time case above.
        assertThat(ended.getFinalFare()).isEqualTo(6649L);
        assertThat(ended.getFinalFare()).isLessThan(15000L).isGreaterThan(0L);
    }

    /** docs/api/ride-lifecycle.md section 13c: a rider-initiated no-progress early end, NOT
     *  safety-tagged, is charged the ordinary cancellation fee - not waived, not the full estimate.
     *  This is one of the three regressions you asked for by name ("rider ends with no progress"). */
    @Test
    void customerEndingTheRideWithNoProgressChargesTheNormalCancellationFee() {
        City city = newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
        Customer customer = newCustomer();
        Driver driver = driverAcceptedFor(city);
        PricingRule rule = newPricingRule(city, VehicleType.CAB); // cancellationFee = 5000 (test helper default).
        Booking booking = rideStartedBooking(customer, city, driver, rule); // startedAt = now-60s, zero distance.

        BookingDetailResponse response = service.endRideEarlyAsCustomer(
                customer.getUserId(), booking.getId(), "feeling unwell", false);

        assertThat(response.status()).isEqualTo(BookingStatus.PAYMENT_PENDING);
        Booking ended = bookings.findById(booking.getId()).orElseThrow();
        assertThat(ended.getFinalFare()).isEqualTo(5000L); // the cancellation fee, not waived.
        List<RideFlag> flags = rideFlags.findByBookingIdAndType(booking.getId(), RideFlagType.EARLY_END);
        assertThat(flags).hasSize(1);
        assertThat(flags.get(0).getRaisedBy()).isEqualTo(com.ladygo.common.domain.ActorType.CUSTOMER);
    }

    /** Same no-progress scenario, but safety-tagged - waived, and raises a RIDER_SAFETY_CANCEL flag
     *  sharing the same weekly/pair cap budget as the pre-start cancelForSafetyConcern. */
    @Test
    void customerEndingTheRideWithNoProgressAndASafetyTagIsWaived() {
        City city = newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
        Customer customer = newCustomer();
        Driver driver = driverAcceptedFor(city);
        PricingRule rule = newPricingRule(city, VehicleType.CAB);
        Booking booking = rideStartedBooking(customer, city, driver, rule);

        BookingDetailResponse response = service.endRideEarlyAsCustomer(
                customer.getUserId(), booking.getId(), "felt unsafe", true);

        assertThat(response.status()).isEqualTo(BookingStatus.PAYMENT_PENDING);
        Booking ended = bookings.findById(booking.getId()).orElseThrow();
        assertThat(ended.getFinalFare()).isZero();
        assertThat(rideFlags.findByBookingIdAndType(booking.getId(), RideFlagType.RIDER_SAFETY_CANCEL)).hasSize(1);
    }

    /** The safety-tagged waiver shares its abuse cap with the pre-start cancelForSafetyConcern path -
     *  a rider can't get a second, uncapped free-exit budget just by waiting until RIDE_STARTED. */
    @Test
    void theSafetyTaggedWaiverSharesItsCapWithThePreStartSafetyCancel() {
        City city = newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
        Customer customer = newCustomer();
        Driver driver = driverAcceptedFor(city);
        PricingRule rule = newPricingRule(city, VehicleType.CAB);
        // Use up the pair cap (default 1) via the pre-start path, same as BookingServiceRideLifecycleTest.
        // ride_flags.booking_id has an FK - needs a real prior booking, not a random UUID. CANCELLED
        // (not an "active" status) so it doesn't collide with uq_bookings_one_active_per_driver when
        // the real rideStartedBooking below claims this same driver.
        Booking priorBooking = newBooking(customer, city, BookingStatus.CANCELLED, driver.getId());
        RideFlag priorSafetyCancel = new RideFlag();
        priorSafetyCancel.setBookingId(priorBooking.getId());
        priorSafetyCancel.setCustomerId(customer.getId());
        priorSafetyCancel.setDriverId(driver.getId());
        priorSafetyCancel.setType(RideFlagType.RIDER_SAFETY_CANCEL);
        priorSafetyCancel.setRaisedBy(com.ladygo.common.domain.ActorType.CUSTOMER);
        rideFlags.saveAndFlush(priorSafetyCancel);
        Booking booking = rideStartedBooking(customer, city, driver, rule);

        BookingDetailResponse response = service.endRideEarlyAsCustomer(
                customer.getUserId(), booking.getId(), "felt unsafe again", true);

        assertThat(response.status()).isEqualTo(BookingStatus.PAYMENT_PENDING);
        Booking ended = bookings.findById(booking.getId()).orElseThrow();
        assertThat(ended.getFinalFare()).isEqualTo(5000L); // cap exceeded - normal fee applies anyway.
        var flag = rideFlags.findAll().stream()
                .filter(f -> f.getBookingId().equals(booking.getId())
                        && f.getType() == RideFlagType.RIDER_SAFETY_CANCEL)
                .findFirst().orElseThrow();
        assertThat(flag.isCapExceeded()).isTrue();
    }

    @Test
    void endingRideEarlyOutsideRideStartedIsRejected() {
        City city = newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
        Customer customer = newCustomer();
        Driver driver = driverAcceptedFor(city);
        PricingRule rule = newPricingRule(city, VehicleType.CAB);
        Booking booking = acceptedBooking(customer, city, driver, rule); // not RIDE_STARTED yet.

        assertThatThrownBy(() -> service.endRideEarlyAsDriver(driver.getUserId(), booking.getId(), "x"))
                .isInstanceOf(BookingStateException.class);
    }

    /** Explicit invariant check (item 4 of the ride-lifecycle security follow-up): no double-complete
     *  under concurrency - a second completion attempt on an already-completed booking must be
     *  rejected, not silently re-process (duplicate fare write, duplicate PAYMENT_PENDING transition,
     *  etc). completeRideInternal's own ALLOWED-map + @Version guard (BookingStateMachine) is what
     *  makes this safe; this proves it end to end through the public service method. */
    @Test
    void completingAnAlreadyCompletedRideIsRejectedNotReprocessed() {
        City city = newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
        Customer customer = newCustomer();
        Driver driver = driverAcceptedFor(city);
        PricingRule rule = newPricingRule(city, VehicleType.CAB);
        Booking booking = rideStartedBooking(customer, city, driver, rule);
        service.completeRide(driver.getUserId(), booking.getId());
        long fareAfterFirstComplete = bookings.findById(booking.getId()).orElseThrow().getFinalFare();

        assertThatThrownBy(() -> service.completeRide(driver.getUserId(), booking.getId()))
                .isInstanceOf(BookingStateException.class);

        // The rejected second attempt must not have mutated anything.
        assertThat(bookings.findById(booking.getId()).orElseThrow().getFinalFare())
                .isEqualTo(fareAfterFirstComplete);
    }

    /** Explicit invariant check: the final fare equals the estimate on a normal completion (never
     *  exceeds, never falls short) and is always positive - the "except via the documented early-end
     *  rules" exception in the invariant is specifically scoped to the early-end path, not this one. */
    @Test
    void normalCompletionFareExactlyEqualsTheEstimateNeverMoreNeverLess() {
        City city = newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
        Customer customer = newCustomer();
        Driver driver = driverAcceptedFor(city);
        PricingRule rule = newPricingRule(city, VehicleType.CAB);
        Booking booking = rideStartedBooking(customer, city, driver, rule);

        service.completeRide(driver.getUserId(), booking.getId());

        long finalFare = bookings.findById(booking.getId()).orElseThrow().getFinalFare();
        assertThat(finalFare).isEqualTo(15000L).isPositive();
    }

    /** docs/api/ride-lifecycle.md section 13c: the early-end recompute is capped at
     *  {@code min(computed, estimatedFare)} - a real OR spoofed-inflated actual distance (the driver's
     *  own reported location_history, not independently-verified ground truth - see the doc's
     *  corrected claim on this) must never be able to push the fare above what the rider originally
     *  agreed to. This is the third of the three regressions you asked for by name ("spoofed inflated
     *  distance on early end"). Simulates the inflated-distance case directly (same mechanism a real
     *  GPS spoof or a genuinely long detour would produce) with a deliberately tiny estimate so an
     *  uncapped recompute would clearly exceed it. */
    @Test
    void earlyEndFareIsCappedAtTheEstimateEvenWithAnInflatedActualDistance() {
        City city = newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
        Customer customer = newCustomer();
        Driver driver = driverAcceptedFor(city);
        PricingRule rule = newPricingRule(city, VehicleType.CAB);
        Booking booking = rideStartedBooking(customer, city, driver, rule);
        booking.setEstimatedFare(100L); // deliberately tiny, so an uncapped recompute would exceed it.
        bookings.saveAndFlush(booking);
        Ride ride = rides.findByBookingId(booking.getId()).orElseThrow();
        ride.setStartedAt(Instant.now().minusSeconds(200));
        rides.saveAndFlush(ride);
        // Simulates a spoofed/inflated location_history trail (or an equally-real long detour) -
        // ~554m, same mechanism as the genuine-progress test above, enough to compute a recompute of
        // ~6649 if uncapped.
        Instant t0 = Instant.now().minusSeconds(150);
        jdbc.update("INSERT INTO location_history (ride_id, driver_id, location, recorded_at) "
                + "VALUES (?, ?, CAST(ST_SetSRID(ST_MakePoint(?, ?), 4326) AS geography), ?)",
                ride.getId(), driver.getId(), 74.80, 34.08, java.sql.Timestamp.from(t0));
        jdbc.update("INSERT INTO location_history (ride_id, driver_id, location, recorded_at) "
                + "VALUES (?, ?, CAST(ST_SetSRID(ST_MakePoint(?, ?), 4326) AS geography), ?)",
                ride.getId(), driver.getId(), 74.806, 34.08, java.sql.Timestamp.from(t0.plusSeconds(60)));

        service.endRideEarlyAsDriver(driver.getUserId(), booking.getId(), "rider asked to stop");

        long finalFare = bookings.findById(booking.getId()).orElseThrow().getFinalFare();
        assertThat(finalFare).isEqualTo(100L); // capped at the estimate, not the ~6649 raw recompute.
    }

    @Test
    void confirmArrivalRejectsBeforeTheWaitElapses() {
        City city = newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
        Customer customer = newCustomer();
        Driver driver = driverAcceptedFor(city);
        PricingRule rule = newPricingRule(city, VehicleType.CAB);
        Booking booking = acceptedBooking(customer, city, driver, rule);
        service.markArriving(driver.getUserId(), booking.getId());

        assertThatThrownBy(() -> service.confirmArrival(customer.getUserId(), booking.getId()))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void confirmArrivalSucceedsAfterTheWaitAndFlags() {
        City city = newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
        Customer customer = newCustomer();
        Driver driver = driverAcceptedFor(city);
        PricingRule rule = newPricingRule(city, VehicleType.CAB);
        Booking booking = acceptedBooking(customer, city, driver, rule);
        service.markArriving(driver.getUserId(), booking.getId());
        flushAndClear();
        jdbc.update("UPDATE bookings SET driver_arriving_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(10, ChronoUnit.MINUTES)), booking.getId());
        flushAndClear();

        BookingDetailResponse response = service.confirmArrival(customer.getUserId(), booking.getId());

        assertThat(response.status()).isEqualTo(BookingStatus.DRIVER_ARRIVED);
        List<RideFlag> flags = rideFlags.findByBookingIdAndType(booking.getId(), RideFlagType.RIDER_CONFIRMED_ARRIVAL);
        assertThat(flags).hasSize(1);
    }

    @Test
    void confirmCashCollectedThenDisputeWithinTheWindowFlagsAndADisputeOutsideItIsRejected() {
        City city = newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
        Customer customer = newCustomer();
        Driver driver = driverAcceptedFor(city);
        PricingRule rule = newPricingRule(city, VehicleType.CAB);
        Booking booking = rideStartedBooking(customer, city, driver, rule);
        service.completeRide(driver.getUserId(), booking.getId()); // -> PAYMENT_PENDING.

        service.confirmCashCollected(driver.getUserId(), booking.getId());
        assertThat(bookings.findById(booking.getId()).orElseThrow().getCashConfirmedAt()).isNotNull();

        service.disputeCash(customer.getUserId(), booking.getId(), "paid by card, not cash");
        List<RideFlag> flags = rideFlags.findByBookingIdAndType(booking.getId(), RideFlagType.CASH_DISPUTE);
        assertThat(flags).hasSize(1);
    }

    /** Security review finding (missing-idempotency-guard / race-condition): disputeCash used to be a
     *  plain read-then-write with no guard against a repeat call - a flaky client retry (or two
     *  concurrent requests) could each pass the "not yet disputed" check and both insert their own
     *  RideFlag for the same dispute, duplicating the admin queue entry. The atomic claim
     *  (BookingRepository.markCashDisputedIfNotAlready) makes a second call a no-op. */
    @Test
    void disputingTheSameCashConfirmationTwiceOnlyRaisesOneFlag() {
        City city = newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
        Customer customer = newCustomer();
        Driver driver = driverAcceptedFor(city);
        PricingRule rule = newPricingRule(city, VehicleType.CAB);
        Booking booking = rideStartedBooking(customer, city, driver, rule);
        service.completeRide(driver.getUserId(), booking.getId());
        service.confirmCashCollected(driver.getUserId(), booking.getId());

        service.disputeCash(customer.getUserId(), booking.getId(), "first dispute call");
        service.disputeCash(customer.getUserId(), booking.getId(), "retry of the same dispute");

        List<RideFlag> flags = rideFlags.findByBookingIdAndType(booking.getId(), RideFlagType.CASH_DISPUTE);
        assertThat(flags).hasSize(1);
    }

    @Test
    void disputeCashIsRejectedOutsideTheWindow() {
        City city = newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
        Customer customer = newCustomer();
        Driver driver = driverAcceptedFor(city);
        PricingRule rule = newPricingRule(city, VehicleType.CAB);
        Booking booking = rideStartedBooking(customer, city, driver, rule);
        service.completeRide(driver.getUserId(), booking.getId());
        service.confirmCashCollected(driver.getUserId(), booking.getId());
        flushAndClear();
        jdbc.update("UPDATE bookings SET cash_confirmed_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(2, ChronoUnit.HOURS)), booking.getId());
        flushAndClear();

        assertThatThrownBy(() -> service.disputeCash(customer.getUserId(), booking.getId(), "too late"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void respondToFlagRecordsTheRidersResponse() {
        City city = newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
        Customer customer = newCustomer();
        Driver driver = driverAcceptedFor(city);
        PricingRule rule = newPricingRule(city, VehicleType.CAB);
        Booking booking = rideStartedBooking(customer, city, driver, rule);
        service.endRideEarlyAsDriver(driver.getUserId(), booking.getId(), "stop requested");
        RideFlag flag = rideFlags.findByBookingIdAndType(booking.getId(), RideFlagType.EARLY_END).get(0);

        service.respondToFlag(customer.getUserId(), booking.getId(), flag.getId(), RideFlagRiderResponse.ACKNOWLEDGED);

        RideFlag reloaded = rideFlags.findById(flag.getId()).orElseThrow();
        assertThat(reloaded.getRiderResponse()).isEqualTo(RideFlagRiderResponse.ACKNOWLEDGED);
        assertThat(reloaded.getRiderRespondedAt()).isNotNull();
    }

    @Test
    void respondToFlagRejectsAMismatchedBooking() {
        City city = newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
        Customer customer = newCustomer();
        Driver driver = driverAcceptedFor(city);
        PricingRule rule = newPricingRule(city, VehicleType.CAB);
        Booking booking = rideStartedBooking(customer, city, driver, rule);
        service.endRideEarlyAsDriver(driver.getUserId(), booking.getId(), "stop requested");
        RideFlag flag = rideFlags.findByBookingIdAndType(booking.getId(), RideFlagType.EARLY_END).get(0);

        assertThatThrownBy(() -> service.respondToFlag(
                customer.getUserId(), java.util.UUID.randomUUID(), flag.getId(), RideFlagRiderResponse.DISPUTED))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- B5: safetyConcern on the driver early-end endpoint (docs/api/driver-earnings-ratings.md section 6)

    @Test
    void driverEarlyEndWithSafetyConcernCreatesExactlyOneHighReportCustomerIncidentAndAnAuditEntry() {
        City city = newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
        Customer customer = newCustomer();
        Driver driver = driverAcceptedFor(city);
        PricingRule rule = newPricingRule(city, VehicleType.CAB);
        Booking booking = rideStartedBooking(customer, city, driver, rule);
        Ride ride = rides.findByBookingId(booking.getId()).orElseThrow();

        BookingDetailResponse response = service.endRideEarlyAsDriver(
                driver.getUserId(), booking.getId(), "rider was aggressive", true);

        assertThat(response.status()).isEqualTo(BookingStatus.PAYMENT_PENDING);
        var all = safetyIncidents.findAll().stream().filter(i -> i.getRideId().equals(ride.getId())).toList();
        assertThat(all).hasSize(1);
        var incident = all.get(0);
        assertThat(incident.getType()).isEqualTo(com.ladygo.support.domain.IncidentType.REPORT_CUSTOMER);
        assertThat(incident.getSeverity()).isEqualTo(com.ladygo.support.domain.IncidentSeverity.HIGH);
        assertThat(incident.getStatus()).isEqualTo(com.ladygo.support.domain.IncidentStatus.OPEN);
        assertThat(incident.getReporterId()).isEqualTo(driver.getUserId());
        assertThat(incident.getNotes()).isEqualTo("rider was aggressive");
        assertThat(incident.getLocation()).isNotNull();
        assertThat(incident.getLocation().getX()).isEqualTo(74.80);
        assertThat(incident.getLocation().getY()).isEqualTo(34.08);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'SAFETY_INCIDENT_CREATED' AND target_id = ? "
                        + "AND actor_id = ?", Long.class, incident.getId().toString(), driver.getUserId())).isEqualTo(1L);
    }

    @Test
    void driverEarlyEndWithoutSafetyConcernCreatesNoIncident() {
        City city = newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
        Driver driver = driverAcceptedFor(city);
        Booking booking = rideStartedBooking(newCustomer(), city, driver, newPricingRule(city, VehicleType.CAB));
        Ride ride = rides.findByBookingId(booking.getId()).orElseThrow();

        service.endRideEarlyAsDriver(driver.getUserId(), booking.getId(), "rider asked to stop", false);

        assertThat(safetyIncidents.findAll().stream().filter(i -> i.getRideId().equals(ride.getId()))).isEmpty();
    }

    @Test
    void aRepeatedDriverEarlyEndNeverCreatesASecondIncident() {
        City city = newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
        Driver driver = driverAcceptedFor(city);
        Booking booking = rideStartedBooking(newCustomer(), city, driver, newPricingRule(city, VehicleType.CAB));
        Ride ride = rides.findByBookingId(booking.getId()).orElseThrow();
        service.endRideEarlyAsDriver(driver.getUserId(), booking.getId(), "unsafe", true);

        assertThatThrownBy(() -> service.endRideEarlyAsDriver(driver.getUserId(), booking.getId(), "unsafe", true))
                .isInstanceOf(BookingStateException.class);

        assertThat(safetyIncidents.findAll().stream().filter(i -> i.getRideId().equals(ride.getId()))).hasSize(1);
    }
}
