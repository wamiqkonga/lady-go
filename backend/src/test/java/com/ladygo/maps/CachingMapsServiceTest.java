package com.ladygo.maps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests the caching/metrics decorator in isolation - a mock delegate, no real HTTP call needed. */
class CachingMapsServiceTest {

    private final MapsService delegate = mock(MapsService.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final CachingMapsService caching = new CachingMapsService(delegate, meterRegistry);

    @Test
    void repeatedGeocodeOfTheSameAddressCallsTheDelegateOnlyOnce() {
        GeocodeResult result = new GeocodeResult(new Coordinate(34.08, 74.80), "Lal Chowk");
        when(delegate.geocode("Lal Chowk")).thenReturn(result);

        assertThat(caching.geocode("Lal Chowk")).isEqualTo(result);
        assertThat(caching.geocode("Lal Chowk")).isEqualTo(result);
        assertThat(caching.geocode("Lal Chowk")).isEqualTo(result);

        verify(delegate, times(1)).geocode("Lal Chowk");
    }

    @Test
    void geocodeCacheKeyIsCaseAndWhitespaceInsensitive() {
        GeocodeResult result = new GeocodeResult(new Coordinate(34.08, 74.80), "Lal Chowk");
        when(delegate.geocode(any())).thenReturn(result);

        caching.geocode("Lal Chowk");
        caching.geocode("  LAL CHOWK  ");

        verify(delegate, times(1)).geocode(any());
    }

    @Test
    void differentAddressesAreNotConflated() {
        when(delegate.geocode("A")).thenReturn(new GeocodeResult(new Coordinate(1, 1), "A"));
        when(delegate.geocode("B")).thenReturn(new GeocodeResult(new Coordinate(2, 2), "B"));

        caching.geocode("A");
        caching.geocode("B");

        verify(delegate, times(1)).geocode("A");
        verify(delegate, times(1)).geocode("B");
    }

    @Test
    void repeatedRouteForTheSameOriginDestinationCallsTheDelegateOnlyOnce() {
        Coordinate origin = new Coordinate(34.08, 74.80);
        Coordinate destination = new Coordinate(34.13, 74.87);
        RouteResult route = new RouteResult(7400, 1140, null);
        when(delegate.calculateRoute(origin, destination)).thenReturn(route);

        assertThat(caching.calculateRoute(origin, destination)).isEqualTo(route);
        assertThat(caching.calculateRoute(origin, destination)).isEqualTo(route);

        verify(delegate, times(1)).calculateRoute(origin, destination);
    }

    @Test
    void calculateDistanceMetersNeverTouchesTheDelegateOrAnyCache() {
        when(delegate.calculateDistanceMeters(any(), any())).thenReturn(1234.0);
        double result = caching.calculateDistanceMeters(new Coordinate(0, 0), new Coordinate(1, 1));
        assertThat(result).isEqualTo(1234.0);
    }

    @Test
    void searchPlacesAndPlaceDetailsAreNeverCached() {
        when(delegate.searchPlaces(any(), any(), any())).thenReturn(List.of());
        caching.searchPlaces("q", "session-1", null);
        caching.searchPlaces("q", "session-1", null);
        verify(delegate, times(2)).searchPlaces(any(), any(), any());
    }

    @Test
    void metricsAreRecordedOnlyOnActualDelegateCallsNotOnCacheHits() {
        when(delegate.geocode("X")).thenReturn(new GeocodeResult(new Coordinate(0, 0), "X"));

        caching.geocode("X");
        caching.geocode("X");
        caching.geocode("X");

        assertThat(meterRegistry.get("maps.calls").tag("sku", "geocode").counter().count()).isEqualTo(1.0);
    }
}
