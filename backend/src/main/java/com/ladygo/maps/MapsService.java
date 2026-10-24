package com.ladygo.maps;

import java.util.List;

/**
 * Provider-agnostic maps SPI (ADR-006/ADR-011, docs/api/maps-service.md). {@code calculateETA} named
 * in docs/architecture/backend-architecture.md is deliberately not a separate method here - it is
 * {@code calculateRoute(...).durationSeconds()}; one code path for "how long will this take."
 */
public interface MapsService {

    GeocodeResult geocode(String address);

    GeocodeResult reverseGeocode(double latitude, double longitude);

    RouteResult calculateRoute(Coordinate origin, Coordinate destination);

    /** Straight-line (haversine) distance in meters. Pure math, no API call, never fails. */
    double calculateDistanceMeters(Coordinate a, Coordinate b);

    List<PlaceSuggestion> searchPlaces(String query, String sessionToken, Coordinate biasedNear);

    PlaceDetails getPlaceDetails(String placeId, String sessionToken);
}
