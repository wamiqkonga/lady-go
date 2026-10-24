package com.ladygo.support.web;

import com.ladygo.common.api.ApiResponse;
import com.ladygo.support.service.SupportContactService;
import com.ladygo.support.web.dto.SupportContactResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** docs/product/driver-android-slice4-design.md Q1. Any authenticated user (driver, customer, admin). */
@RestController
@RequestMapping("/api/v1/support")
public class SupportContactController {

    private final SupportContactService service;

    public SupportContactController(SupportContactService service) {
        this.service = service;
    }

    @Operation(operationId = "getSupportContact")
    @GetMapping("/contact")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<SupportContactResponse> contact() {
        return ApiResponse.ok(service.contact());
    }
}
