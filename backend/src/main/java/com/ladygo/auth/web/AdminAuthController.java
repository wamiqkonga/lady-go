package com.ladygo.auth.web;

import com.ladygo.auth.service.AdminAuthService;
import com.ladygo.auth.service.AdminLoginOutcome;
import com.ladygo.auth.web.dto.AdminLoginRequest;
import com.ladygo.auth.web.dto.AdminPasswordChangeRequest;
import com.ladygo.auth.web.dto.EnrolmentResponse;
import com.ladygo.auth.web.dto.MfaEnrollConfirmRequest;
import com.ladygo.auth.web.dto.MfaEnrollResponse;
import com.ladygo.auth.web.dto.TokenResponse;
import com.ladygo.common.api.ApiResponse;
import com.ladygo.security.jwt.AuthPrincipal;
import com.ladygo.security.net.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff login and forced enrolment (docs/security/auth-design.md section 3). Lost-device recovery for
 * an already-enrolled admin is in the {@code admin} module (SUPER_ADMIN-only, not self-service).
 */
@RestController
@RequestMapping("/api/v1/auth/admin")
public class AdminAuthController {

    private final AdminAuthService adminAuthService;
    private final ClientIpResolver clientIpResolver;

    public AdminAuthController(AdminAuthService adminAuthService, ClientIpResolver clientIpResolver) {
        this.adminAuthService = adminAuthService;
        this.clientIpResolver = clientIpResolver;
    }

    @PostMapping("/login")
    public ApiResponse<Object> login(@Valid @RequestBody AdminLoginRequest request, HttpServletRequest httpRequest) {
        AdminLoginOutcome outcome = adminAuthService.login(
                request.email(), request.password(), request.totpCode(), request.deviceId(),
                clientIpResolver.resolve(httpRequest));
        return switch (outcome) {
            case AdminLoginOutcome.Authenticated a ->
                    ApiResponse.ok(new TokenResponse(a.tokens().accessToken(), a.tokens().refreshToken()));
            case AdminLoginOutcome.EnrolmentRequired e ->
                    ApiResponse.ok(new EnrolmentResponse(e.enrolmentToken(), e.remainingSteps()));
        };
    }

    @PostMapping("/password/change")
    @PreAuthorize("hasAuthority('SCOPE_ENROLMENT')")
    public ApiResponse<Void> changePassword(
            @Valid @RequestBody AdminPasswordChangeRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        adminAuthService.changePassword(principal, request.currentPassword(), request.newPassword());
        return ApiResponse.ok(null);
    }

    @PostMapping("/mfa/enroll")
    @PreAuthorize("hasAuthority('SCOPE_ENROLMENT')")
    public ApiResponse<MfaEnrollResponse> startMfaEnrollment(@AuthenticationPrincipal AuthPrincipal principal) {
        var result = adminAuthService.startMfaEnrollment(principal);
        return ApiResponse.ok(new MfaEnrollResponse(result.secret(), result.provisioningUri()));
    }

    @PostMapping("/mfa/enroll/confirm")
    @PreAuthorize("hasAuthority('SCOPE_ENROLMENT')")
    public ApiResponse<Void> confirmMfaEnrollment(
            @Valid @RequestBody MfaEnrollConfirmRequest request, @AuthenticationPrincipal AuthPrincipal principal) {
        adminAuthService.confirmMfaEnrollment(principal, request.code());
        return ApiResponse.ok(null);
    }
}
