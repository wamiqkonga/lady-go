package com.ladygo.admin.web;

import java.util.UUID;
import com.ladygo.admin.service.AdminDriverService;
import com.ladygo.admin.web.dto.AdminDriverActionRequest;
import com.ladygo.admin.web.dto.AdminDriverDetailResponse;
import com.ladygo.admin.web.dto.AdminDriverListItemResponse;
import com.ladygo.auth.service.StepUpGuard;
import com.ladygo.common.api.ApiResponse;
import com.ladygo.common.api.PageResponse;
import com.ladygo.drivers.domain.DriverVerificationStatus;
import com.ladygo.security.jwt.AuthPrincipal;
import com.ladygo.security.net.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin Drivers screen. Reads: SUPPORT and above. Suspend/activate: ADMIN and above, a written reason, a fresh
 * step-up (enforced here, not just in the UI) and an audit row.
 */
@RestController
@RequestMapping("/api/v1/admin/drivers")
public class AdminDriverController {

    private final AdminDriverService service;
    private final StepUpGuard stepUpGuard;
    private final ClientIpResolver clientIpResolver;

    public AdminDriverController(
            AdminDriverService service, StepUpGuard stepUpGuard, ClientIpResolver clientIpResolver) {
        this.service = service;
        this.stepUpGuard = stepUpGuard;
        this.clientIpResolver = clientIpResolver;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPPORT', 'ADMIN', 'SUPER_ADMIN')")
    public ApiResponse<PageResponse<AdminDriverListItemResponse>> listDrivers(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) DriverVerificationStatus verificationStatus,
            Pageable pageable) {
        return ApiResponse.ok(service.list(q, verificationStatus, pageable));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPPORT', 'ADMIN', 'SUPER_ADMIN')")
    public ApiResponse<AdminDriverDetailResponse> getDriver(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping("/{id}/suspend")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ApiResponse<Void> suspendDriver(@PathVariable UUID id, @Valid @RequestBody AdminDriverActionRequest body,
            @AuthenticationPrincipal AuthPrincipal principal, HttpServletRequest request) {
        stepUpGuard.require(principal);
        service.suspend(id, principal.userId(), principal.role().name(), body.reason().trim(),
                clientIpResolver.resolve(request));
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ApiResponse<Void> activateDriver(@PathVariable UUID id, @Valid @RequestBody AdminDriverActionRequest body,
            @AuthenticationPrincipal AuthPrincipal principal, HttpServletRequest request) {
        stepUpGuard.require(principal);
        service.activate(id, principal.userId(), principal.role().name(), body.reason().trim(),
                clientIpResolver.resolve(request));
        return ApiResponse.ok(null);
    }
}
