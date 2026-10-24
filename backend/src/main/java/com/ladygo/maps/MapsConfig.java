package com.ladygo.maps;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.client.RestClient;

/**
 * Wires the real {@link MapsService} for staging/prod only (docs/api/maps-service.md section 3) -
 * {@code LADYGO_GOOGLE_MAPS_API_KEY} is only ever resolved when this bean is actually being created
 * under one of those profiles, so dev/test never needs a placeholder value for it.
 */
@Configuration
@Profile({"staging", "prod"})
public class MapsConfig {

    @Bean
    MapsService mapsService(
            RestClient.Builder restClientBuilder,
            MeterRegistry meterRegistry,
            @Value("${LADYGO_GOOGLE_MAPS_API_KEY}") String apiKey) {
        GoogleMapsService real = new GoogleMapsService(restClientBuilder, apiKey);
        return new CachingMapsService(real, meterRegistry);
    }
}
