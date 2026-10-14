package com.ladygo.locations.web;

import java.util.List;
import com.ladygo.common.api.ApiResponse;
import com.ladygo.locations.repository.CityRepository;
import com.ladygo.locations.web.dto.CityResponse;
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

    public CityController(CityRepository cities) {
        this.cities = cities;
    }

    @GetMapping
    public ApiResponse<List<CityResponse>> list() {
        return ApiResponse.ok(cities.findByActiveTrue().stream()
                .map(city -> new CityResponse(city.getId(), city.getName(), city.getCountry()))
                .toList());
    }
}
