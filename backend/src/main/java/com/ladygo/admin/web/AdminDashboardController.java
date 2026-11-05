package com.ladygo.admin.web;

import com.ladygo.admin.service.AdminDashboardService;
import com.ladygo.admin.web.dto.AdminDashboardResponse;
import com.ladygo.common.api.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Dashboard figures: read only, open to SUPPORT and above, counts and timestamps only. */
@RestController
@RequestMapping("/api/v1/admin/dashboard")
@PreAuthorize("hasAnyRole('SUPPORT', 'ADMIN', 'SUPER_ADMIN')")
public class AdminDashboardController {

    private final AdminDashboardService service;

    public AdminDashboardController(AdminDashboardService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<AdminDashboardResponse> dashboard() {
        return ApiResponse.ok(service.snapshot());
    }
}
