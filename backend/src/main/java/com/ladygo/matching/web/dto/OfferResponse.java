package com.ladygo.matching.web.dto;

import java.time.Instant;
import java.util.UUID;
import com.ladygo.vehicles.domain.VehicleType;

/**
 * What an offered-but-not-yet-accepting driver sees - deliberately coarse. docs/api/matching.md
 * section 3: NO exact pickup address, NO exact coordinates, NO rider identity - only an approximate
 * area (rounded to {@code booking.pickup.approximate.radius.m}), vehicle type, and the fare.
 */
public record OfferResponse(
        UUID offerId,
        UUID bookingId,
        VehicleType vehicleType,
        Double approximatePickupLatitude,
        Double approximatePickupLongitude,
        Long estimatedFare,
        String currency,
        Integer distanceMeters,
        Instant expiresAt) {
}
