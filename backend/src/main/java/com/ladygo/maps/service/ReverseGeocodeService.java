package com.ladygo.maps.service;

import com.ladygo.config.ConfigurationService;
import com.ladygo.locations.domain.City;
import com.ladygo.locations.repository.CityRepository;
import com.ladygo.maps.GeocodeResult;
import com.ladygo.maps.MapsService;
import com.ladygo.maps.web.ReverseGeocodeResponse;
import com.ladygo.security.ratelimit.RateLimitExceededException;
import com.ladygo.security.ratelimit.RateLimiter;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * docs/product/map-design.md section 5.1. Turns a settled map pin into an address. The "only call this
 * when the pin stops moving" rule is enforced by the apps; this class bounds a single user's calls with
 * the same per-user bucket the booking estimate uses, because both reach the same paid provider
 * (ADR-011), and reports whether the point is inside a city Lady Go serves.
 */
@Service
public class ReverseGeocodeService {

    private final MapsService mapsService;
    private final CityRepository cities;
    private final RateLimiter rateLimiter;
    private final ConfigurationService config;

    public ReverseGeocodeService(
            MapsService mapsService, CityRepository cities, RateLimiter rateLimiter, ConfigurationService config) {
        this.mapsService = mapsService;
        this.cities = cities;
        this.rateLimiter = rateLimiter;
        this.config = config;
    }

    // Deliberately not @Transactional: the shared rate limiter writes its bucket row, which a read-only
    // transaction would reject (found by running the app; the city lookup is a single read on its own).
    public ReverseGeocodeResponse reverseGeocode(UUID userId, double latitude, double longitude) {
        enforceRateLimit(userId);
        Optional<City> city = cities.findServingCity(longitude, latitude);
        // A provider outage propagates as MapsServiceException (503 MAPS_UNAVAILABLE); the apps fall back
        // to the device geocoder, so the pin still works without the provider.
        GeocodeResult result = mapsService.reverseGeocode(latitude, longitude);
        return new ReverseGeocodeResponse(
                latitude, longitude, result.formattedAddress(), city.isPresent(), city.map(City::getName).orElse(null));
    }

    private void enforceRateLimit(UUID userId) {
        int maxAttempts = config.getInt("bookings.maps.rate.max.attempts", 20);
        Duration window = config.getMinutes("bookings.maps.rate.window.minutes", Duration.ofMinutes(1));
        if (!rateLimiter.tryConsume("maps_calls:" + userId, maxAttempts, window)) {
            throw new RateLimitExceededException();
        }
    }
}
