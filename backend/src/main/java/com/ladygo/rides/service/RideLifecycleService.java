package com.ladygo.rides.service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.bookings.repository.BookingRepository;
import com.ladygo.bookings.service.BookingService;
import com.ladygo.bookings.service.BookingStateMachine;
import com.ladygo.bookings.service.RidePinService;
import com.ladygo.bookings.web.dto.BookingDetailResponse;
import com.ladygo.common.domain.ActorType;
import com.ladygo.common.exception.BookingStateException;
import com.ladygo.common.exception.ResourceNotFoundException;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.config.ConfigurationService;
import com.ladygo.customers.domain.Customer;
import com.ladygo.customers.repository.CustomerRepository;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.repository.DriverRepository;
import com.ladygo.payments.domain.PaymentMode;
import com.ladygo.pricing.domain.PricingRule;
import com.ladygo.pricing.repository.PricingRuleRepository;
import com.ladygo.pricing.service.FareCalculator;
import com.ladygo.pricing.service.FareResult;
import com.ladygo.realtime.RealtimePushService;
import com.ladygo.rides.domain.Ride;
import com.ladygo.rides.domain.RideFlag;
import com.ladygo.rides.domain.RideFlagRiderResponse;
import com.ladygo.rides.domain.RideFlagType;
import com.ladygo.rides.domain.RideStatus;
import com.ladygo.rides.repository.RideFlagRepository;
import com.ladygo.rides.repository.RideRepository;
import com.ladygo.security.ratelimit.RateLimitExceededException;
import com.ladygo.security.ratelimit.RateLimiter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Arrive, PIN-start, complete, and early-end (docs/api/ride-lifecycle.md). The one place
 * {@code bookings.status} moves through DRIVER_ACCEPTED..PAYMENT_PENDING, on top of
 * {@link com.ladygo.bookings.service.BookingStateMachine} for the actual transition/audit-event write.
 */
@Service
public class RideLifecycleService {

    private final BookingRepository bookings;
    private final DriverRepository drivers;
    private final CustomerRepository customers;
    private final RideRepository rides;
    private final RideFlagRepository rideFlags;
    private final BookingStateMachine stateMachine;
    private final RidePinService ridePinService;
    private final RateLimiter rateLimiter;
    private final ConfigurationService config;
    private final RealtimePushService push;
    private final LocationGateService gate;
    private final PricingRuleRepository pricingRules;
    private final FareCalculator fareCalculator;
    private final JdbcTemplate jdbc;
    private final BookingService bookingService;

    public RideLifecycleService(
            BookingRepository bookings, DriverRepository drivers, CustomerRepository customers, RideRepository rides,
            RideFlagRepository rideFlags, BookingStateMachine stateMachine,
            RidePinService ridePinService, RateLimiter rateLimiter, ConfigurationService config,
            RealtimePushService push, LocationGateService gate, PricingRuleRepository pricingRules,
            FareCalculator fareCalculator, JdbcTemplate jdbc, BookingService bookingService) {
        this.bookings = bookings;
        this.drivers = drivers;
        this.customers = customers;
        this.rides = rides;
        this.rideFlags = rideFlags;
        this.stateMachine = stateMachine;
        this.ridePinService = ridePinService;
        this.rateLimiter = rateLimiter;
        this.config = config;
        this.push = push;
        this.gate = gate;
        this.pricingRules = pricingRules;
        this.fareCalculator = fareCalculator;
        this.jdbc = jdbc;
        this.bookingService = bookingService;
    }

    @Transactional
    public BookingDetailResponse markArriving(UUID driverUserId, UUID bookingId) {
        Driver driver = resolveDriver(driverUserId);
        Booking booking = requireDriverBooking(driver, bookingId);
        if (booking.getStatus() == BookingStatus.DRIVER_ARRIVING) {
            return bookingService.getForDriver(driverUserId, bookingId); // idempotent re-call.
        }
        Booking updated = stateMachine.transition(
                bookingId, BookingStatus.DRIVER_ARRIVING, ActorType.DRIVER, driver.getId(), "driver_arriving");
        updated.setDriverArrivingAt(Instant.now());
        bookings.save(updated);
        return bookingService.getForDriver(driverUserId, bookingId);
    }

    /** Hard gate (docs/api/ride-lifecycle.md section 3): fails closed on stale/missing/too-far location. */
    @Transactional
    public BookingDetailResponse markArrived(UUID driverUserId, UUID bookingId) {
        Driver driver = resolveDriver(driverUserId);
        Booking booking = requireDriverBooking(driver, bookingId);
        if (booking.getStatus() == BookingStatus.DRIVER_ARRIVED) {
            return bookingService.getForDriver(driverUserId, bookingId); // idempotent re-call.
        }
        gate.requireWithinRadius(driver, booking.getPickupLocation(), "the pickup point");
        stateMachine.transition(bookingId, BookingStatus.DRIVER_ARRIVED, ActorType.DRIVER, driver.getId(),
                "driver_arrived");
        return bookingService.getForDriver(driverUserId, bookingId);
    }

    /**
     * docs/api/ride-lifecycle.md section 13b-4 - once DRIVER_ARRIVING has run long enough, the rider
     * may confirm arrival themselves rather than stay blocked on a stuck driver-side gate. Always
     * flagged for review; never a silent pass.
     */
    @Transactional
    public BookingDetailResponse confirmArrival(UUID customerUserId, UUID bookingId) {
        Customer customer = resolveCustomer(customerUserId);
        Booking booking = bookings.findById(bookingId)
                .filter(b -> b.getCustomerId().equals(customer.getId()))
                .orElseThrow(ResourceNotFoundException::new);
        if (booking.getStatus() != BookingStatus.DRIVER_ARRIVING) {
            throw new BookingStateException();
        }
        Duration wait = config.getSeconds("ride.gate.rider_confirm_wait.seconds", Duration.ofSeconds(300));
        if (booking.getDriverArrivingAt() == null
                || Duration.between(booking.getDriverArrivingAt(), Instant.now()).compareTo(wait) < 0) {
            throw new ValidationException("Please wait a little longer before confirming your driver has arrived.");
        }
        Driver driver = drivers.findById(booking.getDriverId()).orElseThrow(ResourceNotFoundException::new);
        stateMachine.transition(
                bookingId, BookingStatus.DRIVER_ARRIVED, ActorType.CUSTOMER, customer.getId(), "rider_confirmed_arrival");
        raiseFlag(booking, driver, RideFlagType.RIDER_CONFIRMED_ARRIVAL, ActorType.CUSTOMER, null, false);
        return bookingService.get(customerUserId, bookingId);
    }

    /**
     * PIN + proximity gate (docs/api/ride-lifecycle.md section 5). A durable per-ride lock trips once
     * {@link RidePinService}'s own rolling-window attempt cap is exceeded, upgrading what would
     * otherwise be a 15-minute cooldown into a persistent lock, and raises a flag + frees the rider's
     * safety-concern cancel. A separate per-driver bucket (across all rides) catches a driver cycling
     * through many different riders' PINs.
     *
     * <p>Security review finding, MEDIUM: both exceptions below are thrown only BEFORE any
     * status-mutating write in this method (gate/transition/ride-row updates all happen strictly
     * after a successful PIN verification), so {@code noRollbackFor} here only protects the rate
     * limiter's own attempt-counter write and this method's own lockRidePinAndFlag write - without
     * it, Spring's default rollback-on-RuntimeException would silently erase BOTH on every single
     * rejected attempt (not just the final, lock-triggering one), since reporting "incorrect PIN" or
     * "rate limited" to the caller is this method's deliberate unhappy-path return value, not a sign
     * that something went wrong that should be undone.
     */
    @Transactional(noRollbackFor = {ValidationException.class, RateLimitExceededException.class})
    public BookingDetailResponse startRide(UUID driverUserId, UUID bookingId, String pin) {
        Driver driver = resolveDriver(driverUserId);
        Booking booking = requireDriverBooking(driver, bookingId);
        if (booking.getStatus() != BookingStatus.DRIVER_ARRIVED) {
            throw new BookingStateException();
        }
        if (booking.getRidePinLockedAt() != null) {
            throw new ValidationException(
                    "The ride PIN has been locked after too many incorrect attempts. The rider can cancel for a safety concern at no charge.");
        }
        enforceDriverWidePinRateLimit(driver.getId());
        try {
            if (!ridePinService.verify(bookingId, pin)) {
                throw new ValidationException("Incorrect ride PIN.");
            }
        } catch (RateLimitExceededException ex) {
            lockRidePinAndFlag(booking, driver);
            throw ex;
        }
        gate.requireWithinRadius(driver, booking.getPickupLocation(), "the pickup point");

        stateMachine.transition(bookingId, BookingStatus.RIDE_STARTED, ActorType.DRIVER, driver.getId(), "ride_started");
        Ride ride = rides.findByBookingId(bookingId).orElseThrow(ResourceNotFoundException::new);
        ride.setStatus(RideStatus.STARTED);
        ride.setStartedAt(Instant.now());
        ride.setStartLocation(driver.getCurrentLocation());
        rides.save(ride);
        return bookingService.getForDriver(driverUserId, bookingId);
    }

    /** Normal end of trip - driver-only. Far-from-destination is flagged, never blocked. */
    @Transactional
    public BookingDetailResponse completeRide(UUID driverUserId, UUID bookingId) {
        Driver driver = resolveDriver(driverUserId);
        Booking booking = requireDriverBooking(driver, bookingId);
        if (booking.getStatus() != BookingStatus.RIDE_STARTED) {
            throw new BookingStateException();
        }
        completeRideInternal(booking, driver, false, ActorType.DRIVER, driver.getId(), "ride_completed");
        gate.distanceIfFresh(driver, booking.getDestinationLocation()).filter(gate::isFar).ifPresent(
                distance -> raiseFlag(
                        booking, driver, RideFlagType.COMPLETION_FAR_FROM_DESTINATION, ActorType.DRIVER, distance, false));
        return bookingService.getForDriver(driverUserId, bookingId);
    }

    /** Either party may end a started ride early - fare is recomputed with the minimum-fare floor,
     *  and it's always flagged. A driver-initiated end far from the destination also prompts the rider. */
    @Transactional
    public BookingDetailResponse endRideEarlyAsDriver(UUID driverUserId, UUID bookingId, String reason) {
        Driver driver = resolveDriver(driverUserId);
        Booking booking = requireDriverBooking(driver, bookingId);
        if (booking.getStatus() != BookingStatus.RIDE_STARTED) {
            throw new BookingStateException();
        }
        completeRideInternal(booking, driver, true, ActorType.DRIVER, driver.getId(), "ride_ended_early: " + reason);
        Integer distance = gate.distanceIfFresh(driver, booking.getDestinationLocation()).orElse(null);
        boolean far = distance != null && gate.isFar(distance);
        RideFlag flag = raiseFlag(booking, driver, RideFlagType.EARLY_END, ActorType.DRIVER, distance, false);
        if (far) {
            customers.findById(booking.getCustomerId()).ifPresent(customer -> push.pushToUser(
                    customer.getUserId(), "/queue/ride-flag", new RideFlagPrompt(flag.getId(), flag.getType())));
        }
        return bookingService.getForDriver(driverUserId, bookingId);
    }

    @Transactional
    public BookingDetailResponse endRideEarlyAsCustomer(UUID customerUserId, UUID bookingId, String reason) {
        Customer customer = resolveCustomer(customerUserId);
        Booking booking = bookings.findById(bookingId)
                .filter(b -> b.getCustomerId().equals(customer.getId()))
                .orElseThrow(ResourceNotFoundException::new);
        if (booking.getStatus() != BookingStatus.RIDE_STARTED) {
            throw new BookingStateException();
        }
        Driver driver = drivers.findById(booking.getDriverId()).orElseThrow(ResourceNotFoundException::new);
        completeRideInternal(booking, driver, true, ActorType.CUSTOMER, customer.getId(), "ride_ended_early: " + reason);
        raiseFlag(booking, driver, RideFlagType.EARLY_END, ActorType.CUSTOMER, null, false);
        return bookingService.get(customerUserId, bookingId);
    }

    /** docs/api/ride-lifecycle.md section 8 - driver-only; starts the rider's dispute window. */
    @Transactional
    public BookingDetailResponse confirmCashCollected(UUID driverUserId, UUID bookingId) {
        Driver driver = resolveDriver(driverUserId);
        Booking booking = requireDriverBooking(driver, bookingId);
        if (booking.getStatus() != BookingStatus.PAYMENT_PENDING) {
            throw new BookingStateException();
        }
        if (booking.getPaymentMethod() != PaymentMode.CASH) {
            throw new ValidationException("This booking isn't paid by cash.");
        }
        if (booking.getCashConfirmedAt() != null) {
            throw new ValidationException("Cash has already been confirmed for this ride.");
        }
        booking.setCashConfirmedAt(Instant.now());
        bookings.save(booking);
        return bookingService.getForDriver(driverUserId, bookingId);
    }

    /** docs/api/ride-lifecycle.md section 8 - rider-only, within the dispute window; stops the
     *  auto-complete sweep for this booking until admin resolution (Phase 9).
     *
     * <p>Security review finding, HIGH: this used to only insert a {@link RideFlag}, never writing to
     * the {@code bookings} row itself - with no {@code @Version} bump to race against, a dispute
     * landing in the same window as a concurrent {@link CashConfirmationSweepJob} tick could lose
     * silently (the sweep reads "no dispute yet" and completes the booking anyway). Setting
     * {@code cashDisputedAt} on the booking gives this an actual row write, so the sweep's own save
     * now fails with the same {@code OptimisticLockingFailureException} every other concurrent
     * booking transition already relies on - see the sweep job for the other half of this fix. */
    @Transactional
    public BookingDetailResponse disputeCash(UUID customerUserId, UUID bookingId, String reason) {
        Customer customer = resolveCustomer(customerUserId);
        Booking booking = bookings.findById(bookingId)
                .filter(b -> b.getCustomerId().equals(customer.getId()))
                .orElseThrow(ResourceNotFoundException::new);
        if (booking.getStatus() != BookingStatus.PAYMENT_PENDING || booking.getCashConfirmedAt() == null) {
            throw new ValidationException("There's no cash confirmation to dispute yet.");
        }
        Duration window = config.getMinutes("ride.cash.dispute.window.minutes", Duration.ofMinutes(60));
        if (Duration.between(booking.getCashConfirmedAt(), Instant.now()).compareTo(window) > 0) {
            throw new ValidationException("The dispute window for this ride has closed.");
        }
        // Security review finding: atomic claim, not a plain read-then-write - a repeat or concurrent
        // call that loses the claim (0 rows affected, already disputed by this call or another) must
        // not raise a second RideFlag for the same dispute.
        Instant disputedAt = Instant.now();
        if (bookings.markCashDisputedIfNotAlready(bookingId, disputedAt) == 0) {
            return bookingService.get(customerUserId, bookingId);
        }
        // The bulk update above bypasses this already-loaded entity - keep it in sync with the exact
        // value just written, rather than leaving a stale in-memory read for the rest of this request.
        booking.setCashDisputedAt(disputedAt);
        Driver driver = drivers.findById(booking.getDriverId()).orElseThrow(ResourceNotFoundException::new);
        raiseFlag(booking, driver, RideFlagType.CASH_DISPUTE, ActorType.CUSTOMER, null, false);
        return bookingService.get(customerUserId, bookingId);
    }

    /** Called by {@link CashConfirmationSweepJob} - see its class javadoc for why this is a separate
     *  bean method (own transaction, not a same-bean self-invocation) and why the gate is
     *  {@code cashDisputedAt} rather than a {@code RideFlag} query. */
    @Transactional
    public void autoCompleteCashBookingIfUndisputed(UUID bookingId) {
        Duration window = config.getMinutes("ride.cash.dispute.window.minutes", Duration.ofMinutes(60));
        Booking booking = bookings.findById(bookingId).orElseThrow(ResourceNotFoundException::new);
        if (booking.getStatus() != BookingStatus.PAYMENT_PENDING
                || booking.getPaymentMethod() != PaymentMode.CASH
                || booking.getCashConfirmedAt() == null
                || booking.getCashDisputedAt() != null) {
            return;
        }
        if (Duration.between(booking.getCashConfirmedAt(), Instant.now()).compareTo(window) < 0) {
            return; // dispute window still open.
        }
        stateMachine.transition(bookingId, BookingStatus.COMPLETED, ActorType.SYSTEM, null,
                "cash_confirmed_auto_complete");
    }

    /** docs/api/ride-lifecycle.md section 2.8 - the rider's response to a specific flag that prompted
     *  them (currently only the driver-initiated-early-end-far-from-destination case). */
    @Transactional
    public void respondToFlag(UUID customerUserId, UUID bookingId, UUID flagId, RideFlagRiderResponse response) {
        Customer customer = resolveCustomer(customerUserId);
        RideFlag flag = rideFlags.findById(flagId)
                .filter(f -> f.getCustomerId().equals(customer.getId()) && f.getBookingId().equals(bookingId))
                .orElseThrow(ResourceNotFoundException::new);
        flag.setRiderResponse(response);
        flag.setRiderRespondedAt(Instant.now());
        rideFlags.save(flag);
    }

    private void completeRideInternal(
            Booking booking, Driver driver, boolean early, ActorType actor, UUID actorId, String reason) {
        Ride ride = rides.findByBookingId(booking.getId()).orElseThrow(ResourceNotFoundException::new);
        Instant completedAt = Instant.now();
        int actualDistanceM = sumLocationHistoryDistanceMeters(ride.getId());
        int actualDurationS = ride.getStartedAt() == null ? 0
                : (int) Duration.between(ride.getStartedAt(), completedAt).getSeconds();

        // Security review finding, CRITICAL: ending a ride the instant it starts left
        // actualDistanceM/actualDurationS near zero, which FareCalculator floors to the pricing
        // rule's bare minimum fare - a free way to pay far less than the agreed estimate for any
        // trip, with zero cost or risk. Below this threshold, an early end isn't "genuinely early
        // enough to deserve a smaller fare" - it's charged the full estimate, same as a normal
        // completion; the distance-based recompute only applies once real progress has actually
        // happened. This doesn't address a driver spoofing slow GPS for the WHOLE trip - that's the
        // same already-accepted device-location-trust residual risk from docs/api/matching.md
        // section 3, not a new gap introduced here.
        long finalFare;
        if (early && isGenuinelyEarly(actualDistanceM, actualDurationS)) {
            PricingRule rule = pricingRules.findById(booking.getPricingRuleId()).orElseThrow(ResourceNotFoundException::new);
            FareResult fare = fareCalculator.calculate(rule, actualDistanceM, actualDurationS);
            finalFare = fare.totalFare();
        } else {
            finalFare = booking.getEstimatedFare();
        }
        booking.setFinalFare(finalFare);

        stateMachine.transition(booking.getId(), BookingStatus.RIDE_COMPLETED, actor, actorId, reason);

        ride.setStatus(RideStatus.COMPLETED);
        ride.setCompletedAt(completedAt);
        ride.setEndLocation(driver.getCurrentLocation());
        ride.setActualDistanceM(actualDistanceM);
        ride.setActualDurationS(actualDurationS);
        rides.save(ride);

        stateMachine.transition(booking.getId(), BookingStatus.PAYMENT_PENDING, ActorType.SYSTEM, null, "awaiting_payment");
    }

    /** True only once the ride has covered enough real distance AND real time to make a
     *  distance-based fare recompute meaningful rather than exploitable (see completeRideInternal's
     *  comment). Security review finding: this was originally OR, not AND - elapsed time alone
     *  (parked, doing nothing) satisfied it with zero actual distance, so waiting ~120s and then
     *  ending early still floored the fare to the pricing rule's bare minimum. Real road travel
     *  (not just a running clock) is required on both axes now - the per-minute component of
     *  FareCalculator still means standing time this does count toward a genuine recompute's
     *  duration-based charge, but it can no longer be the ONLY thing that unlocks the recompute path
     *  in place of the full estimate. */
    private boolean isGenuinelyEarly(int actualDistanceM, int actualDurationS) {
        int minElapsedSeconds = config.getInt("ride.early_end.min_elapsed.seconds", 120);
        int minDistanceM = config.getInt("ride.early_end.min_distance.m", 500);
        return actualDurationS >= minElapsedSeconds && actualDistanceM >= minDistanceM;
    }

    private int sumLocationHistoryDistanceMeters(UUID rideId) {
        Double total = jdbc.queryForObject(
                "SELECT COALESCE(SUM(ST_Distance(location, prev_location)), 0) FROM ("
                        + "  SELECT location, LAG(location) OVER (ORDER BY recorded_at) AS prev_location "
                        + "  FROM location_history WHERE ride_id = ?"
                        + ") t",
                Double.class, rideId);
        return total == null ? 0 : (int) Math.round(total);
    }

    private void enforceDriverWidePinRateLimit(UUID driverId) {
        int maxAttempts = config.getInt("ride.pin.driver.max.attempts", 15);
        Duration window = config.getMinutes("ride.pin.driver.window.minutes", Duration.ofMinutes(60));
        if (!rateLimiter.tryConsume("ride_pin_driver:" + driverId, maxAttempts, window)) {
            throw new RateLimitExceededException();
        }
    }

    private void lockRidePinAndFlag(Booking booking, Driver driver) {
        if (booking.getRidePinLockedAt() != null) {
            return; // already locked - no duplicate flag.
        }
        booking.setRidePinLockedAt(Instant.now());
        bookings.save(booking);
        raiseFlag(booking, driver, RideFlagType.PIN_LOCKOUT, ActorType.SYSTEM, null, false);
        customers.findById(booking.getCustomerId()).ifPresent(customer -> push.pushToUser(
                customer.getUserId(), "/queue/ride-flag", new RideFlagPrompt(null, RideFlagType.PIN_LOCKOUT)));
    }

    private RideFlag raiseFlag(
            Booking booking, Driver driver, RideFlagType type, ActorType raisedBy, Integer distanceM,
            boolean capExceeded) {
        RideFlag flag = new RideFlag();
        flag.setRideId(rides.findByBookingId(booking.getId()).map(Ride::getId).orElse(null));
        flag.setBookingId(booking.getId());
        flag.setCustomerId(booking.getCustomerId());
        flag.setDriverId(driver.getId());
        flag.setType(type);
        flag.setRaisedBy(raisedBy);
        flag.setDistanceFromExpectedM(distanceM);
        flag.setCapExceeded(capExceeded);
        return rideFlags.save(flag);
    }

    private Driver resolveDriver(UUID driverUserId) {
        return drivers.findByUserId(driverUserId).orElseThrow(ResourceNotFoundException::new);
    }

    private Customer resolveCustomer(UUID customerUserId) {
        return customers.findByUserId(customerUserId).orElseThrow(ResourceNotFoundException::new);
    }

    private Booking requireDriverBooking(Driver driver, UUID bookingId) {
        return bookings.findById(bookingId)
                .filter(b -> driver.getId().equals(b.getDriverId()))
                .orElseThrow(ResourceNotFoundException::new);
    }

    /** The one payload shape pushed for every flag that may prompt the rider - deliberately minimal
     *  (an id to respond against, and the type so the client knows which copy to show). */
    public record RideFlagPrompt(UUID flagId, RideFlagType type) {
    }
}
