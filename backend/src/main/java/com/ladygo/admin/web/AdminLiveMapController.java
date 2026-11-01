package com.ladygo.admin.web;

import com.ladygo.admin.service.AdminLiveMapService;
import com.ladygo.admin.web.dto.AdminLiveMapResponse;
import com.ladygo.common.api.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Live map snapshot: read only, open to SUPPORT and above. Current positions only, never history. */
@RestController
@RequestMapping("/api/v1/admin/live-map")
@PreAuthorize("hasAnyRole('SUPPORT', 'ADMIN', 'SUPER_ADMIN')")
public class AdminLiveMapController {

    private final AdminLiveMapService service;

    public AdminLiveMapController(AdminLiveMapService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<AdminLiveMapResponse> snapshot() {
        return ApiResponse.ok(service.snapshot());
    }
}
