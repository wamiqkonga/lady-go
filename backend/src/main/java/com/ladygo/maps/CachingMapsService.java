package com.ladygo.maps;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Wraps a real {@link MapsService} with the in-process caching and per-SKU call metrics ADR-011
 * calls for (docs/api/maps-service.md section 4) - kept as a decorator, independent of which
 * provider it wraps, so the caching/metrics logic is unit-testable with a simple mock delegate
 * instead of a real HTTP call. Not applied to {@link MockMapsService} (nothing to save there - it
 * never makes a billed call) or to {@code searchPlaces}/{@code getPlaceDetails} (session tokens are
 * the cost control there, per-session billing is already correct without caching across sessions).
 */
public class CachingMapsService implements MapsService {

    private static final Duration GEOCODE_TTL = Duration.ofHours(24);
    private static final Duration ROUTE_TTL = Duration.ofSeconds(90);
    private static final String METRIC_NAME = "maps.calls";

    private final MapsService delegate;
    private final MeterRegistry meterRegistry;
    private final Cache<String, GeocodeResult> geocodeCache;
    private final Cache<String, RouteResult> routeCache;

    public CachingMapsService(MapsService delegate, MeterRegistry meterRegistry) {
        this.delegate = delegate;
        this.meterRegistry = meterRegistry;
        this.geocodeCache = Caffeine.newBuilder().expireAfterWrite(GEOCODE_TTL).maximumSize(10_000).build();
        this.routeCache = Caffeine.newBuilder().expireAfterWrite(ROUTE_TTL).maximumSize(10_000).build();
    }

    @Override
    public GeocodeResult geocode(String address) {
        String key = "geocode:" + address.trim().toLowerCase(Locale.ROOT);
        return geocodeCache.get(key, k -> {
            count("geocode");
            return delegate.geocode(address);
        });
    }

    @Override
    public GeocodeResult reverseGeocode(double latitude, double longitude) {
        String key = "reverse:" + round(latitude) + "," + round(longitude);
        return geocodeCache.get(key, k -> {
            count("reverse_geocode");
            return delegate.reverseGeocode(latitude, longitude);
        });
    }

    @Override
    public RouteResult calculateRoute(Coordinate origin, Coordinate destination) {
        String key = round(origin.latitude()) + "," + round(origin.longitude())
                + "->" + round(destination.latitude()) + "," + round(destination.longitude());
        return routeCache.get(key, k -> {
            count("route");
            return delegate.calculateRoute(origin, destination);
        });
    }

    @Override
    public double calculateDistanceMeters(Coordinate a, Coordinate b) {
        return delegate.calculateDistanceMeters(a, b);
    }

    @Override
    public List<PlaceSuggestion> searchPlaces(String query, String sessionToken, Coordinate biasedNear) {
        count("places_autocomplete");
        return delegate.searchPlaces(query, sessionToken, biasedNear);
    }

    @Override
    public PlaceDetails getPlaceDetails(String placeId, String sessionToken) {
        count("place_details");
        return delegate.getPlaceDetails(placeId, sessionToken);
    }

    private void count(String sku) {
        meterRegistry.counter(METRIC_NAME, "sku", sku).increment();
    }

    /** 5 decimal places =~ 1.1m - close enough to dedupe near-identical requests, not conflate real ones. */
    private static String round(double value) {
        return String.format(Locale.ROOT, "%.5f", value);
    }
}
