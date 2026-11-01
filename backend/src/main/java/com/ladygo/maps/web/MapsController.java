package com.ladygo.maps.web;

import com.ladygo.common.api.ApiResponse;
import com.ladygo.maps.service.ReverseGeocodeService;
import com.ladygo.security.jwt.AuthPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** docs/product/map-design.md section 5.1: the address behind a pin the user dropped on the map. */
@RestController
@RequestMapping("/api/v1/maps")
@PreAuthorize("hasAnyRole('CUSTOMER', 'DRIVER')")
public class MapsController {

    private final ReverseGeocodeService reverseGeocodeService;

    public MapsController(ReverseGeocodeService reverseGeocodeService) {
        this.reverseGeocodeService = reverseGeocodeService;
    }

    @PostMapping("/reverse-geocode")
    public ApiResponse<ReverseGeocodeResponse> reverseGeocode(
            @AuthenticationPrincipal AuthPrincipal principal, @Valid @RequestBody ReverseGeocodeRequest request) {
        return ApiResponse.ok(
                reverseGeocodeService.reverseGeocode(principal.userId(), request.latitude(), request.longitude()));
    }
}
