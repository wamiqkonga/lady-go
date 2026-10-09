package com.ladygo.admin.web.dto;

import java.time.Instant;
import java.util.UUID;
import com.ladygo.common.domain.ActorType;
import com.ladygo.rides.domain.RideFlagRiderResponse;
import com.ladygo.rides.domain.RideFlagType;

/** docs/product/admin-web-design.md section 4 - admin oversight view of one {@code RideFlag}. */
public record AdminRideFlagResponse(
        UUID id,
        UUID bookingId,
        UUID rideId,
        UUID customerId,
        UUID driverId,
        RideFlagType type,
        ActorType raisedBy,
        Integer distanceFromExpectedM,
        boolean capExceeded,
        RideFlagRiderResponse riderResponse,
        Instant riderRespondedAt,
        UUID adminReviewedBy,
        Instant adminReviewedAt,
        Instant createdAt) {
}
