package com.ladygo.locations.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.ladygo.common.api.ApiResponse;
import com.ladygo.locations.repository.CityRepository;
import com.ladygo.locations.web.dto.CityResponse;
import com.ladygo.pricing.repository.PricingRuleRepository;
import com.ladygo.vehicles.domain.VehicleType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Closes a real gap found while building the driver registration website: {@code POST /drivers/me}
 * requires a {@code cityId}, but nothing exposed which cities exist at all - not inventing a new
 * selection UX, just the minimal list a city picker needs. Authenticated (CUSTOMER or DRIVER), not
 * public - no reason to expose the operating-city list to an unauthenticated caller.
 */
@RestController
@RequestMapping("/api/v1/cities")
@PreAuthorize("hasAnyRole('CUSTOMER', 'DRIVER')")
public class CityController {

    private final CityRepository cities;
    private final PricingRuleRepository pricingRules;

    public CityController(CityRepository cities, PricingRuleRepository pricingRules) {
        this.cities = cities;
        this.pricingRules = pricingRules;
    }

    @GetMapping
    public ApiResponse<List<CityResponse>> list() {
        Map<UUID, List<VehicleType>> faresByCity = new HashMap<>();
        for (Object[] pair : pricingRules.findActiveCityVehicleTypePairs(Instant.now())) {
            faresByCity.computeIfAbsent((UUID) pair[0], id -> new ArrayList<>()).add((VehicleType) pair[1]);
        }
        return ApiResponse.ok(cities.findByActiveTrue().stream()
                .map(city -> new CityResponse(city.getId(), city.getName(), city.getCountry(),
                        faresByCity.getOrDefault(city.getId(), List.of()).stream().sorted().toList()))
                .toList());
    }
}
