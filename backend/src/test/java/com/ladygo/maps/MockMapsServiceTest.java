package com.ladygo.maps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class MockMapsServiceTest {

    private final MockMapsService maps = new MockMapsService();

    @Test
    void sameCoordinateHasZeroDistance() {
        Coordinate point = new Coordinate(34.08, 74.80);
        assertThat(maps.calculateDistanceMeters(point, point)).isZero();
    }

    @Test
    void distanceMatchesKnownHaversineValue() {
        // Lal Chowk to Hazratbal, Srinagar - roughly 7.3-7.5km in a straight line.
        Coordinate lalChowk = new Coordinate(34.0837, 74.7973);
        Coordinate hazratbal = new Coordinate(34.1322, 74.8392);
        double distance = maps.calculateDistanceMeters(lalChowk, hazratbal);
        assertThat(distance).isCloseTo(6500, within(1500.0));
    }

    @Test
    void calculateRouteDerivesDurationFromDistanceAndAssumedSpeed() {
        Coordinate origin = new Coordinate(34.08, 74.80);
        Coordinate destination = new Coordinate(34.13, 74.87);
        RouteResult route = maps.calculateRoute(origin, destination);

        double expectedHours = (route.distanceMeters() / 1000.0) / 25.0;
        int expectedSeconds = (int) Math.round(expectedHours * 3600);
        assertThat(route.durationSeconds()).isEqualTo(expectedSeconds);
        assertThat(route.distanceMeters()).isEqualTo((int) Math.round(maps.calculateDistanceMeters(origin, destination)));
    }

    @Test
    void geocodeEchoesAFormattedAddress() {
        GeocodeResult result = maps.geocode("Lal Chowk");
        assertThat(result.formattedAddress()).contains("Lal Chowk");
    }

    @Test
    void reverseGeocodeEchoesTheCoordinate() {
        GeocodeResult result = maps.reverseGeocode(34.08, 74.80);
        assertThat(result.coordinate().latitude()).isEqualTo(34.08);
        assertThat(result.coordinate().longitude()).isEqualTo(74.80);
    }

    @Test
    void searchPlacesReturnsAFixedNonEmptyList() {
        assertThat(maps.searchPlaces("lal", "session-1", null)).isNotEmpty();
    }

    @Test
    void getPlaceDetailsReturnsSomethingForAnyId() {
        PlaceDetails details = maps.getPlaceDetails("mock-place-1", "session-1");
        assertThat(details.coordinate()).isNotNull();
    }
}
