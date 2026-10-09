package com.ladygo.rides.web.dto;

import java.time.Instant;

/** docs/api/safety.md section 4 - the deliberately minimal public-facing shape: a driver's first
 *  name (never their full name), vehicle, plate, and live location. No phone numbers, no rider
 *  details, nothing else from {@code BookingDetailResponse}'s much larger shape. */
public record TripShareLookupResponse(
        String driverFirstName,
        String vehicleMake,
        String vehicleModel,
        String vehicleColor,
        String vehicleRegistration,
        Double latitude,
        Double longitude,
        Instant locationAt) {
}
