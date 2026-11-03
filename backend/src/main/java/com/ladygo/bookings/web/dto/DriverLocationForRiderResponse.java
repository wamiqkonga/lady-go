package com.ladygo.bookings.web.dto;

import java.time.Instant;

/**
 * Where the rider's own driver is right now (docs/product/map-design.md section 5, release 2). When {@code live} is
 * false there is nothing to show - the ride is not in a state where the rider may see the driver, or the driver has
 * not reported a recent position - and the coordinates are null, so a stale point can never be drawn as current.
 */
public record DriverLocationForRiderResponse(boolean live, Double latitude, Double longitude, Instant updatedAt) {

    public static DriverLocationForRiderResponse notLive() {
        return new DriverLocationForRiderResponse(false, null, null, null);
    }
}
