package com.ladygo.admin.web;

import java.util.List;
import com.ladygo.admin.service.AdminUserManagementService;
import com.ladygo.admin.web.dto.AdminUserInviteRequest;
import com.ladygo.admin.web.dto.AdminUserReasonRequest;
import com.ladygo.admin.web.dto.AdminUserResponse;
import com.ladygo.admin.web.dto.AdminUserRoleRequest;
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
import org.springframework.web.bind.annotation.GetMapping;
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
    private final AdminUserManagementService management;

    public AdminUserController(AdminAuthService adminAuthService, ClientIpResolver clientIpResolver,
            com.ladygo.auth.service.StepUpGuard stepUpGuard, AdminUserManagementService management) {
        this.management = management;
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

    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<List<AdminUserResponse>> list() {
        return ApiResponse.ok(management.list());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<AdminUserResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(management.get(id));
    }

    /** Invites a new staff member: creates the account with no password and emails a single-use set-password link. */
    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<AdminUserResponse> invite(
            @Valid @RequestBody AdminUserInviteRequest body, @AuthenticationPrincipal AuthPrincipal principal,
            HttpServletRequest request) {
        stepUpGuard.require(principal);
        return ApiResponse.ok(management.invite(principal.userId(), body.email(), body.name(), body.role(),
                body.reason(), clientIpResolver.resolve(request)));
    }

    @PostMapping("/{id}/role")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<AdminUserResponse> changeRole(
            @PathVariable UUID id, @Valid @RequestBody AdminUserRoleRequest body,
            @AuthenticationPrincipal AuthPrincipal principal, HttpServletRequest request) {
        stepUpGuard.require(principal);
        return ApiResponse.ok(management.changeRole(principal.userId(), id, body.role(), body.reason(),
                clientIpResolver.resolve(request)));
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<AdminUserResponse> deactivate(
            @PathVariable UUID id, @Valid @RequestBody AdminUserReasonRequest body,
            @AuthenticationPrincipal AuthPrincipal principal, HttpServletRequest request) {
        stepUpGuard.require(principal);
        return ApiResponse.ok(management.deactivate(principal.userId(), id, body.reason(),
                clientIpResolver.resolve(request)));
    }

    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<AdminUserResponse> reactivate(
            @PathVariable UUID id, @Valid @RequestBody AdminUserReasonRequest body,
            @AuthenticationPrincipal AuthPrincipal principal, HttpServletRequest request) {
        stepUpGuard.require(principal);
        return ApiResponse.ok(management.reactivate(principal.userId(), id, body.reason(),
                clientIpResolver.resolve(request)));
    }
}
