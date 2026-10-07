package com.ladygo.drivers.service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.bookings.repository.BookingRepository;
import com.ladygo.common.exception.ResourceNotFoundException;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.common.geo.GeoPoints;
import com.ladygo.config.ConfigurationService;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.domain.DriverAvailability;
import com.ladygo.drivers.repository.DriverRepository;
import com.ladygo.drivers.web.dto.DriverLocationRequest;
import com.ladygo.rides.domain.Ride;
import com.ladygo.rides.repository.RideRepository;
import com.ladygo.security.ratelimit.RateLimitExceededException;
import com.ladygo.security.ratelimit.RateLimiter;
import org.locationtech.jts.geom.Point;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * docs/api/matching.md section 3: throttled, validated driver location reporting. Rejects stale
 * timestamps and implausible speed jumps (location-spoofing defence); writes {@code location_history}
 * only during an active ride, at a sampled interval (privacy by design, per V7's own migration
 * comment), never while just idly ONLINE.
 */
@Service
public class DriverLocationService {

    private static final Set<BookingStatus> ACTIVE_RIDE_STATUSES = EnumSet.of(
            BookingStatus.DRIVER_ACCEPTED, BookingStatus.DRIVER_ARRIVING,
            BookingStatus.DRIVER_ARRIVED, BookingStatus.RIDE_STARTED);

    private final DriverRepository drivers;
    private final BookingRepository bookings;
    private final RideRepository rides;
    private final RateLimiter rateLimiter;
    private final ConfigurationService config;
    private final JdbcTemplate jdbc;

    public DriverLocationService(
            DriverRepository drivers, BookingRepository bookings, RideRepository rides, RateLimiter rateLimiter,
            ConfigurationService config, JdbcTemplate jdbc) {
        this.drivers = drivers;
        this.bookings = bookings;
        this.rides = rides;
        this.rateLimiter = rateLimiter;
        this.config = config;
        this.jdbc = jdbc;
    }

    @Transactional
    public void updateLocation(UUID userId, DriverLocationRequest request) {
        Driver driver = drivers.findByUserId(userId).orElseThrow(ResourceNotFoundException::new);
        if (driver.getAvailabilityStatus() == DriverAvailability.OFFLINE) {
            throw new ValidationException("Driver is OFFLINE.");
        }
        enforceRateLimit(driver.getId());
        validateTimestamp(request.recordedAt());

        Point newPoint = GeoPoints.of(request.latitude(), request.longitude());
        validateSpeed(driver, newPoint, request.recordedAt());

        driver.setCurrentLocation(newPoint);
        driver.setCurrentLocationAt(request.recordedAt());
        drivers.save(driver);

        sampleLocationHistoryIfOnActiveRide(driver, newPoint, request.recordedAt());
    }

    private void enforceRateLimit(UUID driverId) {
        int maxAttempts = config.getInt("driver.location.rate.max.attempts", 1);
        Duration window = config.getSeconds("driver.location.rate.window.seconds", Duration.ofSeconds(2));
        if (!rateLimiter.tryConsume("driver_location:" + driverId, maxAttempts, window)) {
            throw new RateLimitExceededException();
        }
    }

    private void validateTimestamp(Instant recordedAt) {
        Instant now = Instant.now();
        Duration maxAge = config.getSeconds("driver.location.max_age.seconds", Duration.ofSeconds(30));
        Duration maxFutureSkew = config.getSeconds("driver.location.max_future_skew.seconds", Duration.ofSeconds(5));
        if (recordedAt.isBefore(now.minus(maxAge))) {
            throw new ValidationException("Location update is too old.");
        }
        if (recordedAt.isAfter(now.plus(maxFutureSkew))) {
            throw new ValidationException("Location update timestamp is in the future.");
        }
    }

    /** Rejects a physically implausible jump since the driver's last known point - a cheap, effective
     *  defence against a spoofed/teleporting GPS feed. Nothing to compare against on the very first
     *  update ever, so that one is allowed through. An out-of-order or duplicate-timestamp update is
     *  rejected outright rather than silently skipped (adversarial review finding, MEDIUM: skipping
     *  let an attacker bypass the speed check entirely - and still have the point applied - just by
     *  sending a non-increasing timestamp on an otherwise-teleporting update). */
    private void validateSpeed(Driver driver, Point newPoint, Instant recordedAt) {
        Point lastPoint = driver.getCurrentLocation();
        Instant lastAt = driver.getCurrentLocationAt();
        if (lastPoint == null || lastAt == null) {
            return;
        }
        if (!recordedAt.isAfter(lastAt)) {
            throw new ValidationException("Location update is out of order.");
        }
        double elapsedSeconds = Duration.between(lastAt, recordedAt).toMillis() / 1000.0;
        double distanceMeters = GeoPoints.haversineMeters(lastPoint, newPoint);
        double speedKmh = (distanceMeters / elapsedSeconds) * 3.6;
        int maxSpeedKmh = config.getInt("driver.location.max_speed.kmh", 150);
        if (speedKmh > maxSpeedKmh) {
            throw new ValidationException("Location update implies an impossible speed.");
        }
    }

    private void sampleLocationHistoryIfOnActiveRide(Driver driver, Point point, Instant recordedAt) {
        Booking activeBooking = bookings.findByDriverIdAndStatusIn(driver.getId(), ACTIVE_RIDE_STATUSES).orElse(null);
        if (activeBooking == null) {
            return;
        }
        Ride ride = rides.findByBookingId(activeBooking.getId()).orElse(null);
        if (ride == null) {
            return; // best-effort telemetry - never block the location update itself on this.
        }

        int sampleIntervalSeconds = config.getInt("tracking.sample.interval.s", 10);
        List<Instant> lastSampled = jdbc.query(
                "SELECT recorded_at FROM location_history WHERE ride_id = ? ORDER BY recorded_at DESC LIMIT 1",
                (rs, i) -> rs.getTimestamp("recorded_at").toInstant(), ride.getId());
        if (!lastSampled.isEmpty()
                && Duration.between(lastSampled.get(0), recordedAt).getSeconds() < sampleIntervalSeconds) {
            return;
        }

        jdbc.update(
                "INSERT INTO location_history (ride_id, driver_id, location, recorded_at) "
                        + "VALUES (?, ?, CAST(ST_SetSRID(ST_MakePoint(?, ?), 4326) AS geography), ?)",
                ride.getId(), driver.getId(), point.getX(), point.getY(), Timestamp.from(recordedAt));
    }
}
