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
 * docs/api/matching.md section 3: throttled, validated driver location reporting. Writes
 * {@code location_history} only during an active ride, at a sampled interval (privacy by design, per
 * V7's own migration comment), never while just idly ONLINE.
 *
 * <p>Staleness is judged from the SERVER's own receive time, never the device's clock (a device whose
 * clock is skewed - wrong, or just behind after being offline - must not have every update rejected
 * as "too old" just because its own clock disagrees with real time; a round trip that genuinely took
 * too long is a server-time fact, not a device-clock one). The device-supplied {@code recordedAt} is
 * kept only to ORDER consecutive updates relative to each other, for the teleport-speed check's
 * elapsed-time denominator - never compared against {@code Instant.now()} directly. If more than
 * {@code driver.location.max_age.seconds} (by server clock) has passed since the last accepted
 * update, the speed check is skipped entirely for this one (treated as a fresh start, same as the very
 * first update ever) rather than comparing against a now-meaningless baseline - this is exactly what
 * makes a post-reconnect batch of buffered points work: each point in the batch arrives moments apart
 * in real (server) time even though its own device timestamp is old.
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

        Instant serverReceivedAt = Instant.now();
        Point newPoint = GeoPoints.of(request.latitude(), request.longitude());
        validateSpeedIfRecentEnoughToCompare(driver, newPoint, request.recordedAt(), serverReceivedAt);

        driver.setCurrentLocation(newPoint);
        driver.setCurrentLocationAt(serverReceivedAt);
        driver.setCurrentLocationDeviceRecordedAt(request.recordedAt());
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

    /** Rejects a physically implausible jump since the driver's last known point - a cheap, effective
     *  defence against a spoofed/teleporting GPS feed. Nothing to compare against on the very first
     *  update ever, so that one is allowed through, and likewise if the last accepted update is older
     *  (by SERVER clock) than {@code driver.location.max_age.seconds} - too much real time has passed
     *  to judge a meaningful speed from it, so this is treated the same as a fresh start rather than
     *  compared against a stale baseline (this is what makes a post-reconnect batch of buffered points
     *  work correctly - see the class javadoc). An out-of-order or duplicate DEVICE timestamp is
     *  rejected outright rather than silently skipped (adversarial review finding, MEDIUM: skipping let
     *  an attacker bypass the speed check entirely - and still have the point applied - just by sending
     *  a non-increasing timestamp on an otherwise-teleporting update). */
    private void validateSpeedIfRecentEnoughToCompare(
            Driver driver, Point newPoint, Instant deviceRecordedAt, Instant serverReceivedAt) {
        Point lastPoint = driver.getCurrentLocation();
        Instant lastServerReceivedAt = driver.getCurrentLocationAt();
        Instant lastDeviceRecordedAt = driver.getCurrentLocationDeviceRecordedAt();
        if (lastPoint == null || lastServerReceivedAt == null || lastDeviceRecordedAt == null) {
            return;
        }
        Duration maxAge = config.getSeconds("driver.location.max_age.seconds", Duration.ofSeconds(60));
        if (Duration.between(lastServerReceivedAt, serverReceivedAt).compareTo(maxAge) > 0) {
            return; // too long since we last heard from this driver, by OUR clock - fresh start.
        }
        if (!deviceRecordedAt.isAfter(lastDeviceRecordedAt)) {
            throw new ValidationException("Location update is out of order.");
        }
        // Security review finding, MEDIUM: an unbounded device delta lets a malicious client inflate
        // the elapsed-time denominator to mask any distance jump as a low speed, while the server-side
        // gap stays within max_age the whole time. Capping the device delta at max_age bounds a single
        // call's "free" distance to maxSpeed * max_age - it doesn't fully close chaining this across
        // many rapid calls, but it closes the single-call unlimited-distance bypass and matches the
        // window we already treat as "too stale to compare" (deliberately not bounding against the
        // server delta directly - see docs/api/matching.md section 3 for why that would break the
        // legitimate skewed-clock and reconnect-batch cases this design exists to support).
        Duration cappedElapsed = Duration.between(lastDeviceRecordedAt, deviceRecordedAt);
        if (cappedElapsed.compareTo(maxAge) > 0) {
            cappedElapsed = maxAge;
        }
        double elapsedSeconds = cappedElapsed.toMillis() / 1000.0;
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
