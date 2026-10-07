package com.ladygo.bookings.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingEvent;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.bookings.repository.BookingEventRepository;
import com.ladygo.bookings.repository.BookingRepository;
import com.ladygo.bookings.web.dto.BookingDetailResponse;
import com.ladygo.bookings.web.dto.BookingRequest;
import com.ladygo.bookings.web.dto.DriverSummaryResponse;
import com.ladygo.bookings.web.dto.FareEstimateRequest;
import com.ladygo.bookings.web.dto.FareEstimateResponse;
import com.ladygo.common.api.PageResponse;
import com.ladygo.common.domain.ActorType;
import com.ladygo.common.exception.ForbiddenException;
import com.ladygo.common.exception.OutOfServiceAreaException;
import com.ladygo.common.exception.ResourceNotFoundException;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.common.geo.GeoPoints;
import com.ladygo.config.ConfigurationService;
import com.ladygo.customers.domain.Customer;
import com.ladygo.customers.repository.CustomerRepository;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.repository.DriverRepository;
import com.ladygo.locations.domain.City;
import com.ladygo.locations.repository.CityRepository;
import com.ladygo.maps.Coordinate;
import com.ladygo.maps.MapsService;
import com.ladygo.maps.MapsServiceException;
import com.ladygo.maps.RouteResult;
import com.ladygo.pricing.domain.PricingRule;
import com.ladygo.pricing.repository.PricingRuleRepository;
import com.ladygo.pricing.service.FareCalculator;
import com.ladygo.pricing.service.FareResult;
import com.ladygo.security.ratelimit.RateLimitExceededException;
import com.ladygo.security.ratelimit.RateLimiter;
import com.ladygo.users.domain.User;
import com.ladygo.users.repository.UserRepository;
import com.ladygo.vehicles.domain.Vehicle;
import com.ladygo.vehicles.domain.VehicleType;
import com.ladygo.vehicles.repository.VehicleRepository;
import com.ladygo.verification.service.EligibilityReportService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** docs/api/booking.md. */
@Service
public class BookingService {

    private final CustomerRepository customers;
    private final UserRepository users;
    private final DriverRepository drivers;
    private final VehicleRepository vehicles;
    private final CityRepository cities;
    private final PricingRuleRepository pricingRules;
    private final BookingRepository bookings;
    private final BookingEventRepository bookingEvents;
    private final BookingStateMachine stateMachine;
    private final BookingCreationTransaction creationTransaction;
    private final EligibilityPolicy eligibilityPolicy;
    private final RidePinService ridePinService;
    private final MapsService mapsService;
    private final FareCalculator fareCalculator;
    private final ConfigurationService config;
    private final RateLimiter rateLimiter;
    private final EligibilityReportService eligibilityReportService;

    public BookingService(
            CustomerRepository customers,
            UserRepository users,
            DriverRepository drivers,
            VehicleRepository vehicles,
            CityRepository cities,
            PricingRuleRepository pricingRules,
            BookingRepository bookings,
            BookingEventRepository bookingEvents,
            BookingStateMachine stateMachine,
            BookingCreationTransaction creationTransaction,
            EligibilityPolicy eligibilityPolicy,
            RidePinService ridePinService,
            MapsService mapsService,
            FareCalculator fareCalculator,
            ConfigurationService config,
            RateLimiter rateLimiter,
            EligibilityReportService eligibilityReportService) {
        this.customers = customers;
        this.users = users;
        this.drivers = drivers;
        this.vehicles = vehicles;
        this.cities = cities;
        this.pricingRules = pricingRules;
        this.bookings = bookings;
        this.bookingEvents = bookingEvents;
        this.stateMachine = stateMachine;
        this.creationTransaction = creationTransaction;
        this.eligibilityPolicy = eligibilityPolicy;
        this.ridePinService = ridePinService;
        this.mapsService = mapsService;
        this.fareCalculator = fareCalculator;
        this.config = config;
        this.rateLimiter = rateLimiter;
        this.eligibilityReportService = eligibilityReportService;
    }

    /** docs/api/booking.md section 1. Degrades to straight-line + approximate:true on a Maps outage. */
    public FareEstimateResponse estimate(UUID userId, FareEstimateRequest request) {
        enforceMapsRateLimit(userId);
        City city = resolveCity(request.pickupLatitude(), request.pickupLongitude());
        Coordinate origin = new Coordinate(request.pickupLatitude(), request.pickupLongitude());
        Coordinate destination = new Coordinate(request.destinationLatitude(), request.destinationLongitude());

        RouteOutcome route;
        try {
            RouteResult result = mapsService.calculateRoute(origin, destination);
            route = new RouteOutcome(result.distanceMeters(), result.durationSeconds(), false);
        } catch (MapsServiceException ex) {
            route = approximateRoute(origin, destination);
        }

        enforceMaxDistance(route.distanceMeters());
        PricingRule rule = activeRule(city.getId(), request.vehicleType());
        FareResult fare = fareCalculator.calculate(rule, route.distanceMeters(), route.durationSeconds());
        return toEstimateResponse(city, request.vehicleType(), route, rule, fare);
    }

    /** docs/api/booking.md section 2. Fails closed (503) on a Maps outage - no fallback, unlike {@link #estimate}. */
    @Transactional
    public BookingCreationResult create(UUID userId, BookingRequest request, String idempotencyKey) {
        Customer customer = resolveCustomer(userId);

        Optional<Booking> existing = bookings.findByCustomerIdAndIdempotencyKey(customer.getId(), idempotencyKey);
        if (existing.isPresent()) {
            return new BookingCreationResult(toDetail(existing.get()), false);
        }
        enforceMapsRateLimit(userId);

        EligibilityDecision customerEligibility = eligibilityPolicy.checkCustomerEligibility(customer);
        if (!customerEligibility.eligible()) {
            throw new ForbiddenException(customerEligibility.reasonIfIneligible());
        }
        EligibilityDecision companionsEligibility = eligibilityPolicy.checkCompanions(request);
        if (!companionsEligibility.eligible()) {
            throw new ForbiddenException(companionsEligibility.reasonIfIneligible());
        }

        City city = resolveCity(request.pickupLatitude(), request.pickupLongitude());
        Coordinate origin = new Coordinate(request.pickupLatitude(), request.pickupLongitude());
        Coordinate destination = new Coordinate(request.destinationLatitude(), request.destinationLongitude());
        RouteResult route = mapsService.calculateRoute(origin, destination); // no fallback - propagates as 503.

        enforceMaxDistance(route.distanceMeters());
        PricingRule rule = activeRule(city.getId(), request.vehicleType());
        FareResult fare = fareCalculator.calculate(rule, route.distanceMeters(), route.durationSeconds());
        checkFareTolerance(fare.totalFare(), request.expectedFare(),
                toEstimateResponse(city, request.vehicleType(),
                        new RouteOutcome(route.distanceMeters(), route.durationSeconds(), false), rule, fare));

        Booking booking = new Booking();
        booking.setCustomerId(customer.getId());
        booking.setCityId(city.getId());
        booking.setVehicleType(request.vehicleType());
        booking.setPickupLocation(GeoPoints.of(request.pickupLatitude(), request.pickupLongitude()));
        booking.setPickupAddress(request.pickupAddress());
        booking.setDestinationLocation(GeoPoints.of(request.destinationLatitude(), request.destinationLongitude()));
        booking.setDestinationAddress(request.destinationAddress());
        booking.setEstimatedDistanceM(route.distanceMeters());
        booking.setEstimatedDurationS(route.durationSeconds());
        booking.setEstimatedFare(fare.totalFare());
        booking.setCurrency(rule.getCurrency());
        booking.setPricingRuleId(rule.getId());
        booking.setPaymentMethod(request.paymentMethod());
        booking.setScheduledAt(request.scheduledAt());
        booking.setIdempotencyKey(idempotencyKey);

        try {
            Booking created = creationTransaction.insert(booking, request.rideSelfieId());
            return new BookingCreationResult(toDetail(created), true);
        } catch (DataIntegrityViolationException ex) {
            // A concurrent request with the same key won the race; its transaction already committed.
            Booking winner = bookings.findByCustomerIdAndIdempotencyKey(customer.getId(), idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "Idempotent insert failed but no winning booking row was found", ex));
            return new BookingCreationResult(toDetail(winner), false);
        }
    }

    public BookingDetailResponse get(UUID userId, UUID bookingId) {
        Customer customer = resolveCustomer(userId);
        Booking booking = bookings.findById(bookingId)
                .filter(b -> b.getCustomerId().equals(customer.getId()))
                .orElseThrow(ResourceNotFoundException::new);
        return toDetail(booking);
    }

    /** docs/api/booking.md section 5. */
    @Transactional
    public BookingDetailResponse cancel(UUID userId, UUID bookingId, String reason) {
        Customer customer = resolveCustomer(userId);
        Booking booking = bookings.findById(bookingId)
                .filter(b -> b.getCustomerId().equals(customer.getId()))
                .orElseThrow(ResourceNotFoundException::new);

        Map<String, Object> metadata = assessCancellationFee(booking);
        Booking cancelled = stateMachine.transition(
                bookingId, BookingStatus.CANCELLED, ActorType.CUSTOMER, customer.getId(), reason, metadata);
        return toDetail(cancelled);
    }

    /**
     * docs/api/driver-onboarding.md section 5 - a driver may cancel without penalty on an eligibility
     * concern; this auto-creates an eligibility report for admins, with the customer as the subject.
     * Penalty-free only up to a configurable weekly cap (per driver, across all riders) and a tighter
     * per-driver/rider-pair cap - adversarial review finding: the original "never rate-limited" design
     * was not an approved decision, so beyond either cap the cancellation still goes through but takes
     * the normal cancellation fee, and the auto-created report is flagged for admin review.
     */
    @Transactional
    public BookingDetailResponse cancelForEligibilityConcern(UUID driverUserId, UUID bookingId, String reason) {
        Driver driver = drivers.findByUserId(driverUserId).orElseThrow(ResourceNotFoundException::new);
        Booking booking = bookings.findById(bookingId)
                .filter(b -> driver.getId().equals(b.getDriverId()))
                .orElseThrow(ResourceNotFoundException::new);

        boolean capExceeded = eligibilityReportService.wouldExceedDriverCancelCap(driverUserId, booking.getCustomerId());
        Map<String, Object> metadata = capExceeded
                ? Map.of("cancellationFee", standardCancellationFee(booking), "currency", booking.getCurrency())
                : Map.of("cancellationFee", 0, "currency", booking.getCurrency());

        Booking cancelled = stateMachine.transition(
                bookingId, BookingStatus.CANCELLED, ActorType.DRIVER, driver.getId(), reason, metadata);
        eligibilityReportService.autoReportFromDriverCancellation(
                bookingId, driverUserId, booking.getCustomerId(), reason, capExceeded);
        return toDetail(cancelled);
    }

    private int standardCancellationFee(Booking booking) {
        return pricingRules.findActive(booking.getCityId(), booking.getVehicleType(), Instant.now())
                .map(rule -> (int) rule.getCancellationFee())
                .orElse(0);
    }

    public PageResponse<BookingDetailResponse> history(UUID userId, Pageable pageable) {
        Customer customer = resolveCustomer(userId);
        Page<Booking> page = bookings.findByCustomerIdOrderByCreatedAtDesc(customer.getId(), pageable);
        return PageResponse.of(page.map(this::toHistoryItem));
    }

    /**
     * The metadata map's values must be plain {@code int}, not {@code long} - Hibernate's dirty-check
     * for the JSON-mapped {@code metadata} column takes a deep-copy snapshot via JSON
     * serialize/deserialize; a bare JSON number round-trips back as {@code Integer}, so a {@code Long}
     * value here never compares equal to its own snapshot and triggers a spurious UPDATE against the
     * append-only {@code booking_events} table (a real bug caught by the DB's own append-only trigger).
     */
    private Map<String, Object> assessCancellationFee(Booking booking) {
        if (booking.getStatus() == BookingStatus.REQUESTED || booking.getStatus() == BookingStatus.SEARCHING_DRIVER) {
            return Map.of("cancellationFee", 0, "currency", booking.getCurrency());
        }

        Instant driverAcceptedAt = bookingEvents.findByBookingIdOrderByCreatedAtAsc(booking.getId()).stream()
                .filter(event -> event.getToStatus() == BookingStatus.DRIVER_ACCEPTED)
                .map(BookingEvent::getCreatedAt)
                .findFirst()
                .orElse(null);

        int graceSeconds = config.getInt("cancellation.grace.seconds", 120);
        boolean withinGrace = driverAcceptedAt != null
                && Instant.now().isBefore(driverAcceptedAt.plusSeconds(graceSeconds));
        if (withinGrace) {
            return Map.of("cancellationFee", 0, "currency", booking.getCurrency());
        }

        int fee = pricingRules.findActive(booking.getCityId(), booking.getVehicleType(), Instant.now())
                .map(rule -> (int) rule.getCancellationFee())
                .orElse(0);
        return Map.of("cancellationFee", fee, "currency", booking.getCurrency());
    }

    private City resolveCity(double latitude, double longitude) {
        return cities.findServingCity(longitude, latitude).orElseThrow(OutOfServiceAreaException::new);
    }

    private PricingRule activeRule(UUID cityId, VehicleType vehicleType) {
        return pricingRules.findActive(cityId, vehicleType, Instant.now()).orElseThrow(ResourceNotFoundException::new);
    }

    /**
     * Bounds how many times a single customer can trigger a real (billed) Maps-provider call per
     * window - without this, a single valid JWT could loop {@code /bookings/estimate} with jittered
     * coordinates to force a cache miss every time, defeating ADR-011's cost controls (adversarial
     * review finding, HIGH). One shared bucket per customer across estimate and create, since both
     * call the same paid API.
     */
    private void enforceMapsRateLimit(UUID userId) {
        int maxAttempts = config.getInt("bookings.maps.rate.max.attempts", 20);
        Duration window = config.getMinutes("bookings.maps.rate.window.minutes", Duration.ofMinutes(1));
        if (!rateLimiter.tryConsume("maps_calls:" + userId, maxAttempts, window)) {
            throw new RateLimitExceededException();
        }
    }

    private void enforceMaxDistance(int distanceMeters) {
        int maxDistance = config.getInt("ride.max.distance.m", 100_000);
        if (distanceMeters > maxDistance) {
            throw new ValidationException("That trip is further than Lady Go currently supports.");
        }
    }

    private RouteOutcome approximateRoute(Coordinate origin, Coordinate destination) {
        double distanceMeters = mapsService.calculateDistanceMeters(origin, destination);
        int speedKmh = config.getInt("maps.mock.average.speed.kmh", 25);
        double hours = (distanceMeters / 1000.0) / speedKmh;
        int durationSeconds = (int) Math.round(hours * 3600);
        return new RouteOutcome((int) Math.round(distanceMeters), durationSeconds, true);
    }

    private void checkFareTolerance(long freshFare, long expectedFare, FareEstimateResponse freshEstimate) {
        BigDecimal diffPct = BigDecimal.valueOf(freshFare).subtract(BigDecimal.valueOf(expectedFare)).abs()
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(expectedFare), 4, RoundingMode.HALF_UP);
        BigDecimal tolerancePct = config.getBigDecimal("booking.fare.change.tolerance.pct", BigDecimal.valueOf(5));
        if (diffPct.compareTo(tolerancePct) > 0) {
            throw new FareChangedException(freshEstimate);
        }
    }

    private FareEstimateResponse toEstimateResponse(
            City city, VehicleType vehicleType, RouteOutcome route, PricingRule rule, FareResult fare) {
        return new FareEstimateResponse(
                city.getId(), vehicleType, rule.getCurrency(),
                route.distanceMeters(), route.durationSeconds(), route.approximate(),
                fare.totalFare(), fare.breakdown(), rule.getId());
    }

    private BookingDetailResponse toDetail(Booking booking) {
        DriverSummaryResponse driver = booking.getDriverId() != null ? buildDriverSummary(booking.getDriverId()) : null;
        String ridePin = booking.getStatus().ordinal() < BookingStatus.RIDE_STARTED.ordinal()
                ? ridePinService.derive(booking.getId())
                : null;
        return new BookingDetailResponse(
                booking.getId(), booking.getStatus(), booking.getPickupAddress(), booking.getDestinationAddress(),
                booking.getVehicleType(), booking.getPaymentMethod(), booking.getEstimatedFare(),
                booking.getFinalFare(), booking.getCurrency(), ridePin, driver,
                booking.getCreatedAt(), booking.getScheduledAt());
    }

    /** History list items omit {@code ridePin} and driver detail (docs/api/booking.md section 6). */
    private BookingDetailResponse toHistoryItem(Booking booking) {
        return new BookingDetailResponse(
                booking.getId(), booking.getStatus(), booking.getPickupAddress(), booking.getDestinationAddress(),
                booking.getVehicleType(), booking.getPaymentMethod(), booking.getEstimatedFare(),
                booking.getFinalFare(), booking.getCurrency(), null, null,
                booking.getCreatedAt(), booking.getScheduledAt());
    }

    private DriverSummaryResponse buildDriverSummary(UUID driverId) {
        Driver driver = drivers.findById(driverId).orElse(null);
        if (driver == null) {
            return null;
        }
        User driverUser = users.findById(driver.getUserId()).orElse(null);
        Vehicle vehicle = vehicles.findByDriverId(driverId).stream().findFirst().orElse(null);
        return new DriverSummaryResponse(
                driverId,
                driverUser != null ? driverUser.getName() : null,
                driverUser != null ? driverUser.getProfilePhotoKey() : null,
                driver.getRatingAvg(),
                vehicle != null ? vehicle.getMake() : null,
                vehicle != null ? vehicle.getModel() : null,
                vehicle != null ? vehicle.getColor() : null,
                vehicle != null ? vehicle.getRegistrationNumber() : null);
    }

    private Customer resolveCustomer(UUID userId) {
        return customers.findByUserId(userId).orElseThrow(ResourceNotFoundException::new);
    }

    private record RouteOutcome(int distanceMeters, int durationSeconds, boolean approximate) {
    }
}
