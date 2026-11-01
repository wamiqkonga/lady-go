package com.ladygo.admin.web;

import com.ladygo.auth.service.AdminAuthService;
import com.ladygo.auth.web.dto.AdminUnlockRequest;
import com.ladygo.common.api.ApiResponse;
import com.ladygo.security.jwt.AuthPrincipal;
import com.ladygo.security.net.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SUPER_ADMIN-only staff management actions (docs/product/admin-flows.md section 1: "manage admin
 * users" is SUPER_ADMIN-only). Lost-device MFA recovery (docs/security/auth-design.md section 3.3) -
 * never self-service, always on another staff member, always audited.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

    private final AdminAuthService adminAuthService;
    private final ClientIpResolver clientIpResolver;
    private final com.ladygo.auth.service.StepUpGuard stepUpGuard;

    public AdminUserController(AdminAuthService adminAuthService, ClientIpResolver clientIpResolver,
            com.ladygo.auth.service.StepUpGuard stepUpGuard) {
        this.adminAuthService = adminAuthService;
        this.clientIpResolver = clientIpResolver;
        this.stepUpGuard = stepUpGuard;
    }

    @PostMapping("/{id}/mfa/reset")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<Void> resetMfa(
            @PathVariable UUID id, @AuthenticationPrincipal AuthPrincipal principal, HttpServletRequest request) {
        stepUpGuard.require(principal);
        adminAuthService.resetMfa(principal.userId(), principal.role(), id, clientIpResolver.resolve(request));
        return ApiResponse.ok(null);
    }

    /** Clears the failed-login wait for a locked-out admin. SUPER_ADMIN, fresh step-up, written reason, audited. */
    @PostMapping("/{id}/unlock")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<Void> unlock(
            @PathVariable UUID id, @Valid @RequestBody AdminUnlockRequest body,
            @AuthenticationPrincipal AuthPrincipal principal, HttpServletRequest request) {
        stepUpGuard.require(principal);
        adminAuthService.unlockLogin(principal.userId(), principal.role(), id, body.reason().trim(),
                clientIpResolver.resolve(request));
        return ApiResponse.ok(null);
    }
}
