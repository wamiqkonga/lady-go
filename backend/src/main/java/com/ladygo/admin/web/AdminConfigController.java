package com.ladygo.admin.web;

import java.util.List;
import java.util.UUID;
import com.ladygo.admin.service.AdminConfigService;
import com.ladygo.auth.service.StepUpGuard;
import com.ladygo.admin.web.dto.AdminConfigEntryResponse;
import com.ladygo.admin.web.dto.AdminConfigUpdateRequest;
import com.ladygo.common.api.ApiResponse;
import com.ladygo.security.jwt.AuthPrincipal;
import com.ladygo.security.net.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/product/admin-web-design.md section 6. Reading config is open to any admin; writing it is
 * SUPER_ADMIN-only, per instruction - a live pricing/safety-threshold change takes effect immediately
 * (no versioning/staged rollout for the pilot, open question 3), so it is deliberately gated tighter
 * than the read side.
 */
@RestController
@RequestMapping("/api/v1/admin/config")
public class AdminConfigController {

    private final AdminConfigService service;
    private final ClientIpResolver clientIpResolver;
    private final StepUpGuard stepUpGuard;

    public AdminConfigController(
            AdminConfigService service, ClientIpResolver clientIpResolver, StepUpGuard stepUpGuard) {
        this.service = service;
        this.clientIpResolver = clientIpResolver;
        this.stepUpGuard = stepUpGuard;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ApiResponse<List<AdminConfigEntryResponse>> list() {
        return ApiResponse.ok(service.list());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<AdminConfigEntryResponse> update(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id,
            @Valid @RequestBody AdminConfigUpdateRequest request, HttpServletRequest httpRequest) {
        stepUpGuard.require(principal);
        return ApiResponse.ok(service.update(
                id, request.value(), request.reason(), principal.userId(), principal.role().name(),
                clientIpResolver.resolve(httpRequest)));
    }
}
