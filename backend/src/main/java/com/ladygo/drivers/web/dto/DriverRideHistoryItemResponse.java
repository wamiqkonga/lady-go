package com.ladygo.drivers.web.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One past ride in the driver's history (docs/api/driver-earnings-ratings.md section 4.1). Deliberately has
 * no rider name, phone, selfie or coordinates. {@code status} is COMPLETED, CANCELLED or ENDED_EARLY.
 * {@code myRating} is the score this driver gave the rider (null if not rated yet). {@code ratedByRider} is
 * only whether the rider has rated - the score itself is never shown to the driver (section 4.3: rider
 * ratings are visible to the driver as aggregates only). {@code finalFare} is null when the ride never
 * reached a confirmed fare.
 */
public record DriverRideHistoryItemResponse(
        UUID bookingId, UUID rideId, String status, Instant completedAt, String pickupAddress,
        String destinationAddress, Long finalFare, String currency, String paymentMethod, Short myRating,
        boolean ratedByRider) {
}
