package com.ladygo.admin.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Current positions only (docs/development/plans/2026-10-31-admin-portal-completion.md sections 7-8): one
 * last-known point per driver, never a trail. Names are masked (first name plus last initial) and no phone
 * numbers are included. Each list is capped at {@code limit}; the matching {@code *Truncated} flag says
 * more rows exist than were returned.
 */
public record AdminLiveMapResponse(
        Instant generatedAt,
        int maxAgeMinutes,
        int limit,
        boolean driversTruncated,
        boolean ridesTruncated,
        boolean incidentsTruncated,
        List<LiveDriver> drivers,
        List<LiveRide> rides,
        List<LiveIncident> incidents) {

    /** {@code availability} is ONLINE, BUSY or ON_RIDE. */
    public record LiveDriver(
            UUID driverId, String name, String availability, double latitude, double longitude,
            Instant positionAt, long ageSeconds) {
    }

    public record LiveRide(
            UUID bookingId, String status, UUID driverId, String driverName, String customerName,
            String pickupAddress, double pickupLatitude, double pickupLongitude,
            String dropAddress, double dropLatitude, double dropLongitude) {
    }

    /** Latitude/longitude are null when the incident was raised without a position. */
    public record LiveIncident(
            UUID incidentId, UUID rideId, String type, String severity, String status, Instant createdAt,
            Double latitude, Double longitude) {
    }
}
