package com.ladygo.maps.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ladygo.config.ConfigurationService;
import com.ladygo.locations.repository.CityRepository;
import com.ladygo.maps.Coordinate;
import com.ladygo.maps.GeocodeResult;
import com.ladygo.maps.MapsService;
import com.ladygo.maps.MapsServiceException;
import com.ladygo.maps.web.ReverseGeocodeResponse;
import com.ladygo.security.ratelimit.RateLimitExceededException;
import com.ladygo.security.ratelimit.RateLimiter;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The service in isolation: rate limiting happens before the paid provider call, and outages propagate. */
class ReverseGeocodeServiceTest {

    private final MapsService mapsService = mock(MapsService.class);
    private final CityRepository cities = mock(CityRepository.class);
    private final RateLimiter rateLimiter = mock(RateLimiter.class);
    private final ConfigurationService config = mock(ConfigurationService.class);
    private final ReverseGeocodeService service = new ReverseGeocodeService(mapsService, cities, rateLimiter, config);
    private final UUID userId = UUID.randomUUID();

    ReverseGeocodeServiceTest() {
        when(config.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(config.getMinutes(anyString(), any(Duration.class))).thenAnswer(inv -> inv.getArgument(1));
        when(rateLimiter.tryConsume(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
        when(cities.findServingCity(anyDouble(), anyDouble())).thenReturn(Optional.empty());
    }

    @Test
    void returnsTheProviderAddressAndFlagsAPointOutsideEveryCity() {
        when(mapsService.reverseGeocode(34.08, 74.80))
                .thenReturn(new GeocodeResult(new Coordinate(34.08, 74.80), "Lal Chowk, Srinagar"));

        ReverseGeocodeResponse response = service.reverseGeocode(userId, 34.08, 74.80);

        assertThat(response.address()).isEqualTo("Lal Chowk, Srinagar");
        assertThat(response.inServiceArea()).isFalse();
        assertThat(response.cityName()).isNull();
        assertThat(response.latitude()).isEqualTo(34.08);
        assertThat(response.longitude()).isEqualTo(74.80);
    }

    @Test
    void aRateLimitedUserNeverReachesTheProvider() {
        when(rateLimiter.tryConsume(anyString(), anyInt(), any(Duration.class))).thenReturn(false);

        assertThatThrownBy(() -> service.reverseGeocode(userId, 34.08, 74.80))
                .isInstanceOf(RateLimitExceededException.class);

        verify(mapsService, never()).reverseGeocode(anyDouble(), anyDouble());
    }

    @Test
    void sharesTheBookingEstimateRateBucketForTheSameUser() {
        when(mapsService.reverseGeocode(anyDouble(), anyDouble()))
                .thenReturn(new GeocodeResult(new Coordinate(1, 1), "x"));

        service.reverseGeocode(userId, 1, 1);

        verify(rateLimiter).tryConsume(org.mockito.ArgumentMatchers.eq("maps_calls:" + userId), anyInt(), any(Duration.class));
    }

    /**
     * Found by running the real app: the rate limiter writes its bucket row, so a read-only transaction around
     * this service made every call fail with "cannot execute INSERT in a read-only transaction". The controller
     * tests run inside a test-managed transaction and cannot see this, so guard it directly.
     */
    @Test
    void theServiceIsNotWrappedInATransactionThatForbidsTheRateLimiterWrite() {
        assertThat(ReverseGeocodeService.class.getAnnotation(org.springframework.transaction.annotation.Transactional.class))
                .isNull();
        assertThat(java.util.Arrays.stream(ReverseGeocodeService.class.getDeclaredMethods())
                        .map(m -> m.getAnnotation(org.springframework.transaction.annotation.Transactional.class))
                        .filter(java.util.Objects::nonNull))
                .isEmpty();
    }

    @Test
    void aProviderOutagePropagatesSoTheAppCanFallBackToTheDeviceGeocoder() {
        when(mapsService.reverseGeocode(anyDouble(), anyDouble())).thenThrow(new MapsServiceException(null));

        assertThatThrownBy(() -> service.reverseGeocode(userId, 34.08, 74.80)).isInstanceOf(MapsServiceException.class);
    }
}
