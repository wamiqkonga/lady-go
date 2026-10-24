package com.ladygo.maps;

import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Dev/test-only deterministic stand-in - no network call, same profile-gating pattern as
 * {@code LoggingSmsSender} (docs/security/auth-design.md section 9). Straight-line distance plus an
 * assumed average speed stands in for real road routing, which is good enough for deterministic
 * fare-calculation tests without needing real road data.
 */
@Component
@Profile({"dev", "test"})
public class MockMapsService implements MapsService {

    private static final double EARTH_RADIUS_METERS = 6_371_000;
    private static final double ASSUMED_AVERAGE_SPEED_KMH = 25;

    @Override
    public GeocodeResult geocode(String address) {
        Coordinate coordinate = new Coordinate(34.08, 74.80);
        return new GeocodeResult(coordinate, "Mock address for \"" + address + "\"");
    }

    @Override
    public GeocodeResult reverseGeocode(double latitude, double longitude) {
        return new GeocodeResult(new Coordinate(latitude, longitude),
                String.format("Mock address near %.3f,%.3f", latitude, longitude));
    }

    @Override
    public RouteResult calculateRoute(Coordinate origin, Coordinate destination) {
        double distanceMeters = calculateDistanceMeters(origin, destination);
        double hours = (distanceMeters / 1000.0) / ASSUMED_AVERAGE_SPEED_KMH;
        int durationSeconds = (int) Math.round(hours * 3600);
        return new RouteResult((int) Math.round(distanceMeters), durationSeconds, null);
    }

    @Override
    public double calculateDistanceMeters(Coordinate a, Coordinate b) {
        double lat1 = Math.toRadians(a.latitude());
        double lat2 = Math.toRadians(b.latitude());
        double deltaLat = Math.toRadians(b.latitude() - a.latitude());
        double deltaLon = Math.toRadians(b.longitude() - a.longitude());
        double sinLat = Math.sin(deltaLat / 2);
        double sinLon = Math.sin(deltaLon / 2);
        double h = sinLat * sinLat + Math.cos(lat1) * Math.cos(lat2) * sinLon * sinLon;
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(h));
    }

    @Override
    public List<PlaceSuggestion> searchPlaces(String query, String sessionToken, Coordinate biasedNear) {
        return List.of(
                new PlaceSuggestion("mock-place-1", "Lal Chowk, Srinagar (mock)"),
                new PlaceSuggestion("mock-place-2", "Hazratbal, Srinagar (mock)"),
                new PlaceSuggestion("mock-place-3", "Dal Lake, Srinagar (mock)"));
    }

    @Override
    public PlaceDetails getPlaceDetails(String placeId, String sessionToken) {
        return new PlaceDetails(new Coordinate(34.08, 74.80), "Mock place " + placeId);
    }
}
