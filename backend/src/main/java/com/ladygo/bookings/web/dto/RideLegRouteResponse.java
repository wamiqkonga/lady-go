package com.ladygo.bookings.web.dto;

/**
 * The road a driver follows for one leg of her ride (to the pickup, or from there to the destination), as Google's
 * encoded polyline. All three are null when the maps provider could not be reached: the app then draws a plain line.
 */
public record RideLegRouteResponse(String polyline, Integer distanceMeters, Integer durationSeconds) {
}
