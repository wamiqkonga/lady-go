package com.ladygo.maps.web;

/**
 * The address for a dropped pin, and whether Lady Go serves that point. {@code cityName} is null when
 * {@code inServiceArea} is false, so the app can say "we do not serve this area yet" before the rider
 * reaches the fare screen.
 */
public record ReverseGeocodeResponse(
        double latitude, double longitude, String address, boolean inServiceArea, String cityName) {
}
