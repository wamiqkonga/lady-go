package com.ladygo.rides.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import com.ladygo.common.exception.ResourceNotFoundException;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.config.ConfigurationService;
import com.ladygo.customers.domain.Customer;
import com.ladygo.customers.repository.CustomerRepository;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.repository.DriverRepository;
import com.ladygo.rides.domain.Ride;
import com.ladygo.rides.domain.RideStatus;
import com.ladygo.rides.domain.TripShare;
import com.ladygo.rides.repository.RideRepository;
import com.ladygo.rides.repository.TripShareRepository;
import com.ladygo.rides.web.dto.TripShareCreatedResponse;
import com.ladygo.rides.web.dto.TripShareLookupResponse;
import com.ladygo.security.ratelimit.RateLimitExceededException;
import com.ladygo.security.ratelimit.RateLimiter;
import com.ladygo.users.domain.User;
import com.ladygo.users.repository.UserRepository;
import com.ladygo.vehicles.domain.Vehicle;
import com.ladygo.vehicles.repository.VehicleRepository;
import org.locationtech.jts.geom.Point;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * docs/api/safety.md section 4. Rider-only creation; a public, unauthenticated lookup by token. The
 * raw token is a 256-bit random value, never derived (unlike the ride PIN) - a leaked trip-share link
 * is a much bigger exposure than a 4-digit in-person PIN, so it needs real entropy, not a small
 * keyspace. Only {@code SHA-256(token)} is ever persisted or logged; the raw value is returned exactly
 * once, at creation, and is carried by the public lookup endpoint in a request header rather than a
 * URL path/query - an access log capturing the request line would otherwise capture the secret itself.
 */
@Service
public class TripShareService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32; // 256 bits.

    private final TripShareRepository tripShares;
    private final RideRepository rides;
    private final CustomerRepository customers;
    private final DriverRepository drivers;
    private final UserRepository users;
    private final VehicleRepository vehicles;
    private final ConfigurationService config;
    private final RateLimiter rateLimiter;

    public TripShareService(
            TripShareRepository tripShares, RideRepository rides, CustomerRepository customers,
            DriverRepository drivers, UserRepository users, VehicleRepository vehicles,
            ConfigurationService config, RateLimiter rateLimiter) {
        this.tripShares = tripShares;
        this.rides = rides;
        this.customers = customers;
        this.drivers = drivers;
        this.users = users;
        this.vehicles = vehicles;
        this.config = config;
        this.rateLimiter = rateLimiter;
    }

    /** Rider-only. {@code bookingId} (not {@code rideId}) for API consistency with every other
     *  customer-facing ride-lifecycle endpoint - resolved to the underlying {@link Ride} internally,
     *  since {@code trip_shares.ride_id} is what the schema actually keys on. */
    @Transactional
    public TripShareCreatedResponse create(UUID customerUserId, UUID bookingId) {
        Customer customer = customers.findByUserId(customerUserId).orElseThrow(ResourceNotFoundException::new);
        Ride ride = rides.findByBookingId(bookingId)
                .filter(r -> r.getCustomerId().equals(customer.getId()))
                .orElseThrow(ResourceNotFoundException::new);

        Instant now = Instant.now();
        int maxActive = config.getInt("trip_share.max_active_per_ride", 3);
        long activeCount = tripShares.countByRideIdAndRevokedAtIsNullAndExpiresAtAfter(ride.getId(), now);
        if (activeCount >= maxActive) {
            throw new ValidationException("Too many active trip-share links for this ride.");
        }

        byte[] tokenBytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(tokenBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);

        Duration ttl = config.getHours("trip_share.default_ttl.hours", Duration.ofHours(12));
        TripShare share = new TripShare();
        share.setRideId(ride.getId());
        share.setCreatedBy(customerUserId);
        share.setTokenHash(sha256Hex(rawToken));
        share.setExpiresAt(now.plus(ttl));
        TripShare saved = tripShares.save(share);

        return new TripShareCreatedResponse(saved.getId(), rawToken, saved.getExpiresAt());
    }

    @Transactional
    public void revoke(UUID customerUserId, UUID bookingId, UUID shareId) {
        Customer customer = customers.findByUserId(customerUserId).orElseThrow(ResourceNotFoundException::new);
        Ride ride = rides.findByBookingId(bookingId)
                .filter(r -> r.getCustomerId().equals(customer.getId()))
                .orElseThrow(ResourceNotFoundException::new);
        TripShare share = tripShares.findById(shareId)
                .filter(s -> s.getRideId().equals(ride.getId()))
                .orElseThrow(ResourceNotFoundException::new);
        if (share.getRevokedAt() == null) {
            share.setRevokedAt(Instant.now());
            tripShares.save(share);
        }
    }

    /**
     * Public, unauthenticated. Every failure path - unknown token, revoked, expired, post-ride-grace
     * elapsed, driver/vehicle somehow missing - throws the exact same {@link ResourceNotFoundException}
     * with no distinguishing detail, so a caller can never learn WHY a token didn't work, only that it
     * didn't (security review requirement: identical error for unknown/expired/revoked).
     */
    @Transactional(readOnly = true)
    public TripShareLookupResponse resolve(String rawToken, String clientIp) {
        enforceLookupRateLimits(rawToken, clientIp);

        String computedHash = sha256Hex(rawToken);
        TripShare share = tripShares.findByTokenHash(computedHash)
                // The DB lookup above is already an exact match on an indexed, fixed-length hash (not
                // a raw-secret byte compare with an early-exit timing signal) - this second check is
                // deliberately redundant, using the JDK's constant-time primitive, so the comparison
                // itself is demonstrably constant-time rather than relying only on the DB index's
                // opacity.
                .filter(s -> MessageDigest.isEqual(
                        computedHash.getBytes(StandardCharsets.UTF_8), s.getTokenHash().getBytes(StandardCharsets.UTF_8)))
                .orElseThrow(ResourceNotFoundException::new);

        Instant now = Instant.now();
        if (share.getRevokedAt() != null || now.isAfter(share.getExpiresAt())) {
            throw new ResourceNotFoundException();
        }

        Ride ride = rides.findById(share.getRideId()).orElseThrow(ResourceNotFoundException::new);
        if ((ride.getStatus() == RideStatus.COMPLETED || ride.getStatus() == RideStatus.CANCELLED)) {
            Instant anchor = ride.getCompletedAt() != null ? ride.getCompletedAt() : share.getCreatedAt();
            Duration grace = config.getMinutes("trip_share.post_ride_grace.minutes", Duration.ofMinutes(30));
            if (now.isAfter(anchor.plus(grace))) {
                throw new ResourceNotFoundException();
            }
        }

        Driver driver = drivers.findById(ride.getDriverId()).orElseThrow(ResourceNotFoundException::new);
        User driverUser = users.findById(driver.getUserId()).orElse(null);
        Vehicle vehicle = vehicles.findByDriverId(driver.getId()).stream().findFirst().orElse(null);
        Point location = driver.getCurrentLocation();

        return new TripShareLookupResponse(
                firstNameOf(driverUser != null ? driverUser.getName() : null),
                vehicle != null ? vehicle.getMake() : null,
                vehicle != null ? vehicle.getModel() : null,
                vehicle != null ? vehicle.getColor() : null,
                vehicle != null ? vehicle.getRegistrationNumber() : null,
                location != null ? location.getY() : null,
                location != null ? location.getX() : null,
                driver.getCurrentLocationAt());
    }

    private void enforceLookupRateLimits(String rawToken, String clientIp) {
        int maxPerToken = config.getInt("trip_share.lookup.rate.max.attempts", 10);
        Duration windowPerToken = config.getSeconds("trip_share.lookup.rate.window.seconds", Duration.ofSeconds(60));
        // Bucket-keyed by the token's HASH, never the raw token itself - the rate limiter's own
        // storage must not become a second place the raw secret could ever end up.
        if (!rateLimiter.tryConsume("trip_share_lookup_token:" + sha256Hex(rawToken), maxPerToken, windowPerToken)) {
            throw new RateLimitExceededException();
        }
        if (clientIp != null && !clientIp.isBlank()) {
            int maxPerIp = config.getInt("trip_share.lookup.ip.rate.max.attempts", 30);
            Duration windowPerIp = config.getSeconds("trip_share.lookup.ip.rate.window.seconds", Duration.ofSeconds(60));
            if (!rateLimiter.tryConsume("trip_share_lookup_ip:" + clientIp, maxPerIp, windowPerIp)) {
                throw new RateLimitExceededException();
            }
        }
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 must always be available", ex);
        }
    }

    private static String firstNameOf(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return null;
        }
        return fullName.trim().split("\\s+")[0];
    }
}
