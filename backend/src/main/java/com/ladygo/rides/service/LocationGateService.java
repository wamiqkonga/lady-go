package com.ladygo.rides.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.common.geo.GeoPoints;
import com.ladygo.config.ConfigurationService;
import com.ladygo.drivers.domain.Driver;
import org.locationtech.jts.geom.Point;
import org.springframework.stereotype.Service;

/**
 * The proximity+freshness check behind the ride-lifecycle arrived/start gate (docs/api/ride-lifecycle.md
 * section 3) and the softer far-from-destination signal at completion. Reuses the driver's
 * {@code currentLocation}/{@code currentLocationAt}/{@code currentLocationAccuracyM} already written by
 * {@code DriverLocationService} - no new location write path.
 */
@Service
public class LocationGateService {

    private final ConfigurationService config;

    public LocationGateService(ConfigurationService config) {
        this.config = config;
    }

    /**
     * Hard check for the arrived/start gate - fails closed with a specific, distinct message for each
     * failure reason (never a generic one), per docs/api/ride-lifecycle.md section 13b-4. Never falls
     * back to a stale point for a safety gate.
     */
    public void requireWithinRadius(Driver driver, Point target, String targetLabel) {
        if (driver.getCurrentLocation() == null || driver.getCurrentLocationAt() == null) {
            throw new ValidationException(
                    "No location on file for this driver - send a fresh location update and try again.");
        }
        Duration maxAge = config.getSeconds("ride.gate.max_location_age.seconds", Duration.ofSeconds(30));
        if (Duration.between(driver.getCurrentLocationAt(), Instant.now()).compareTo(maxAge) > 0) {
            throw new ValidationException(
                    "The driver's last known location is too old to confirm this - send a fresh location "
                            + "update and try again.");
        }
        int distanceM = (int) Math.round(GeoPoints.haversineMeters(driver.getCurrentLocation(), target));
        if (distanceM > effectiveRadiusM(driver)) {
            throw new ValidationException("Too far from " + targetLabel + " to confirm this.");
        }
    }

    /**
     * Soft check for the completion-time far-from-destination signal - never blocks. Empty (treated as
     * "not far enough to tell, give the benefit of the doubt") if the driver's location is missing or
     * too stale to judge; otherwise the actual distance in metres.
     */
    public Optional<Integer> distanceIfFresh(Driver driver, Point target) {
        if (driver.getCurrentLocation() == null || driver.getCurrentLocationAt() == null) {
            return Optional.empty();
        }
        Duration maxAge = config.getSeconds("ride.gate.max_location_age.seconds", Duration.ofSeconds(30));
        if (Duration.between(driver.getCurrentLocationAt(), Instant.now()).compareTo(maxAge) > 0) {
            return Optional.empty();
        }
        return Optional.of((int) Math.round(GeoPoints.haversineMeters(driver.getCurrentLocation(), target)));
    }

    public boolean isFar(int distanceM) {
        return distanceM > config.getInt("ride.gate.pickup_radius.m", 200);
    }

    private int effectiveRadiusM(Driver driver) {
        int radius = config.getInt("ride.gate.pickup_radius.m", 200);
        int maxAccuracyCredit = config.getInt("ride.gate.max_accuracy_credit.m", 100);
        double accuracyM = driver.getCurrentLocationAccuracyM() == null ? 0 : driver.getCurrentLocationAccuracyM();
        return radius + (int) Math.round(Math.min(accuracyM, maxAccuracyCredit));
    }
}
