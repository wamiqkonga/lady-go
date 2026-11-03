package com.ladygo.bookings.service;

import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.bookings.repository.BookingRepository;
import com.ladygo.bookings.web.dto.DriverLocationForRiderResponse;
import com.ladygo.common.exception.ResourceNotFoundException;
import com.ladygo.config.ConfigurationService;
import com.ladygo.customers.domain.Customer;
import com.ladygo.customers.repository.CustomerRepository;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.repository.DriverRepository;
import com.ladygo.security.ratelimit.RateLimitExceededException;
import com.ladygo.security.ratelimit.RateLimiter;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.locationtech.jts.geom.Point;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lets a rider see her own driver's position on the map while the ride is live (docs/product/map-design.md).
 * Reads the position {@code DriverLocationService} already stores on the driver - no new write path, and no new
 * tracking: a driver who is not on a ride is never reported here. Only the booking's own customer can ask, and
 * only in the statuses where {@link BookingVisibilityPolicy} already lets her see the driver at all, so this can
 * never reveal a driver earlier than the driver's name and photo are shown.
 */
@Service
public class RiderDriverLocationService {

    private static final Set<BookingStatus> LIVE_STATUSES = EnumSet.of(
            BookingStatus.DRIVER_ACCEPTED, BookingStatus.DRIVER_ARRIVING,
            BookingStatus.DRIVER_ARRIVED, BookingStatus.RIDE_STARTED);

    private final CustomerRepository customers;
    private final BookingRepository bookings;
    private final DriverRepository drivers;
    private final RateLimiter rateLimiter;
    private final ConfigurationService config;

    public RiderDriverLocationService(
            CustomerRepository customers, BookingRepository bookings, DriverRepository drivers,
            RateLimiter rateLimiter, ConfigurationService config) {
        this.customers = customers;
        this.bookings = bookings;
        this.drivers = drivers;
        this.rateLimiter = rateLimiter;
        this.config = config;
    }

    // Not @Transactional: the rate limiter writes its bucket row, which a read-only transaction would reject.
    public DriverLocationForRiderResponse get(UUID userId, UUID bookingId) {
        Customer customer = customers.findByUserId(userId).orElseThrow(ResourceNotFoundException::new);
        Booking booking = bookings.findById(bookingId)
                .filter(b -> b.getCustomerId().equals(customer.getId()))
                .orElseThrow(ResourceNotFoundException::new);
        enforceRateLimit(userId);

        if (!LIVE_STATUSES.contains(booking.getStatus())
                || !BookingVisibilityPolicy.isVisible(
                        BookingViewerRole.CUSTOMER, booking.getStatus(), BookingField.DRIVER_SUMMARY)
                || booking.getDriverId() == null) {
            return DriverLocationForRiderResponse.notLive();
        }
        return drivers.findById(booking.getDriverId()).map(this::freshLocationOf)
                .orElseGet(DriverLocationForRiderResponse::notLive);
    }

    private DriverLocationForRiderResponse freshLocationOf(Driver driver) {
        Point point = driver.getCurrentLocation();
        Instant at = driver.getCurrentLocationAt();
        if (point == null || at == null) {
            return DriverLocationForRiderResponse.notLive();
        }
        Duration maxAge = config.getSeconds("rider.live_location.max_age.seconds", Duration.ofSeconds(60));
        if (Duration.between(at, Instant.now()).compareTo(maxAge) > 0) {
            return DriverLocationForRiderResponse.notLive();
        }
        return new DriverLocationForRiderResponse(true, point.getY(), point.getX(), at);
    }

    private void enforceRateLimit(UUID userId) {
        int maxAttempts = config.getInt("rider.live_location.rate.max.attempts", 30);
        Duration window = config.getMinutes("rider.live_location.rate.window.minutes", Duration.ofMinutes(1));
        if (!rateLimiter.tryConsume("rider_driver_location:" + userId, maxAttempts, window)) {
            throw new RateLimitExceededException();
        }
    }
}
