package com.ladygo.auth.web;

import com.ladygo.auth.service.AccessTokenIssuer;
import com.ladygo.auth.service.AdminAuthService;
import com.ladygo.auth.service.AdminLoginOutcome;
import com.ladygo.auth.service.RefreshTokenService;
import com.ladygo.auth.web.dto.AdminAccessTokenResponse;
import com.ladygo.auth.web.dto.AdminLoginRequest;
import com.ladygo.auth.web.dto.AdminPasswordChangeRequest;
import com.ladygo.auth.web.dto.EnrolmentResponse;
import com.ladygo.auth.web.dto.MfaEnrollConfirmRequest;
import com.ladygo.auth.web.dto.MfaEnrollResponse;
import com.ladygo.common.api.ApiResponse;
import com.ladygo.common.exception.UnauthorizedException;
import com.ladygo.config.ConfigurationService;
import com.ladygo.security.jwt.AuthPrincipal;
import com.ladygo.security.net.ClientIpResolver;
import com.ladygo.users.domain.User;
import com.ladygo.users.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff login and forced enrolment (docs/security/auth-design.md section 3). Lost-device recovery for
 * an already-enrolled admin is in the {@code admin} module (SUPER_ADMIN-only, not self-service).
 *
 * <p>Admin-web-specific credential handling (docs/product/admin-web-design.md section 7): the access
 * token is returned in the body (kept in memory, never persisted, by the SPA); the refresh token
 * never is - it travels only in an {@code HttpOnly} cookie, paired with a double-submit CSRF cookie
 * (see {@link AdminAuthCookies}, {@link AdminCsrfGuard}). CUSTOMER/DRIVER keep the existing
 * JSON-body refresh token in {@code AuthController} - this is a deliberate admin-web-only divergence,
 * not a change to the mobile contract.
 */
@RestController
@RequestMapping("/api/v1/auth/admin")
public class AdminAuthController {

    private final AdminAuthService adminAuthService;
    private final ClientIpResolver clientIpResolver;
    private final RefreshTokenService refreshTokenService;
    private final AccessTokenIssuer accessTokenIssuer;
    private final UserRepository users;
    private final ConfigurationService config;
    private final AdminCsrfGuard csrfGuard;

    public AdminAuthController(
            AdminAuthService adminAuthService, ClientIpResolver clientIpResolver,
            RefreshTokenService refreshTokenService, AccessTokenIssuer accessTokenIssuer, UserRepository users,
            ConfigurationService config, AdminCsrfGuard csrfGuard) {
        this.adminAuthService = adminAuthService;
        this.clientIpResolver = clientIpResolver;
        this.refreshTokenService = refreshTokenService;
        this.accessTokenIssuer = accessTokenIssuer;
        this.users = users;
        this.config = config;
        this.csrfGuard = csrfGuard;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<Object>> login(
            @Valid @RequestBody AdminLoginRequest request, HttpServletRequest httpRequest) {
        AdminLoginOutcome outcome = adminAuthService.login(
                request.email(), request.password(), request.totpCode(), request.deviceId(),
                clientIpResolver.resolve(httpRequest));
        return switch (outcome) {
            case AdminLoginOutcome.Authenticated a -> withNewSessionCookies(a.tokens().refreshToken(),
                    ApiResponse.ok(new AdminAccessTokenResponse(a.tokens().accessToken())));
            case AdminLoginOutcome.EnrolmentRequired e ->
                    ResponseEntity.ok(ApiResponse.ok(new EnrolmentResponse(e.enrolmentToken(), e.remainingSteps())));
        };
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AdminAccessTokenResponse>> refresh(
            @CookieValue(name = AdminAuthCookies.REFRESH_TOKEN, required = false) String refreshToken,
            @CookieValue(name = AdminAuthCookies.CSRF_TOKEN, required = false) String csrfCookie,
            HttpServletRequest httpRequest) {
        csrfGuard.verify(httpRequest, csrfCookie);
        if (refreshToken == null) {
            throw new UnauthorizedException();
        }
        var rotated = refreshTokenService.rotate(refreshToken);
        User user = users.findById(rotated.userId()).orElseThrow(UnauthorizedException::new);
        String access = accessTokenIssuer.issue(user.getId(), user.getRole());
        return withNewSessionCookies(
                rotated.rawRefreshToken(), ApiResponse.ok(new AdminAccessTokenResponse(access)));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @CookieValue(name = AdminAuthCookies.REFRESH_TOKEN, required = false) String refreshToken,
            @CookieValue(name = AdminAuthCookies.CSRF_TOKEN, required = false) String csrfCookie,
            HttpServletRequest httpRequest) {
        csrfGuard.verify(httpRequest, csrfCookie);
        if (refreshToken != null) {
            refreshTokenService.revoke(refreshToken);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, AdminAuthCookies.clearedRefreshTokenCookie().toString())
                .header(HttpHeaders.SET_COOKIE, AdminAuthCookies.clearedCsrfTokenCookie().toString())
                .body(ApiResponse.ok(null));
    }

    private <T> ResponseEntity<ApiResponse<T>> withNewSessionCookies(String refreshToken, ApiResponse<T> body) {
        Duration maxAge = config.getDays("refresh.token.ttl.days", Duration.ofDays(30));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, AdminAuthCookies.refreshTokenCookie(refreshToken, maxAge).toString())
                .header(HttpHeaders.SET_COOKIE,
                        AdminAuthCookies.csrfTokenCookie(AdminAuthCookies.newCsrfToken(), maxAge).toString())
                .body(body);
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
