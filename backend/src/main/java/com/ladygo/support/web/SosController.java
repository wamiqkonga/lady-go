package com.ladygo.support.web;

import com.ladygo.common.api.ApiResponse;
import com.ladygo.security.jwt.AuthPrincipal;
import com.ladygo.support.service.SosService;
import com.ladygo.support.web.dto.SosRequest;
import com.ladygo.support.web.dto.SosResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** docs/api/safety.md section 1: the in-app SOS button, for riders and for drivers. */
@RestController
public class SosController {

    private final SosService sosService;

    public SosController(SosService sosService) {
        this.sosService = sosService;
    }

    @PostMapping("/api/v1/customers/me/sos")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<SosResponse> customerSos(
            @AuthenticationPrincipal AuthPrincipal principal, @Valid @RequestBody SosRequest request) {
        return ApiResponse.ok(sosService.raiseForCustomer(principal.userId(), request));
    }

    @PostMapping("/api/v1/drivers/me/sos")
    @PreAuthorize("hasRole('DRIVER')")
    public ApiResponse<SosResponse> driverSos(
            @AuthenticationPrincipal AuthPrincipal principal, @Valid @RequestBody SosRequest request) {
        return ApiResponse.ok(sosService.raiseForDriver(principal.userId(), request));
    }
}
