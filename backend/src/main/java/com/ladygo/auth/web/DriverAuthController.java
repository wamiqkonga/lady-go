package com.ladygo.auth.web;

import com.ladygo.auth.service.DriverPasswordService;
import com.ladygo.auth.service.TokenPair;
import com.ladygo.auth.web.dto.DriverPasswordChangeRequest;
import com.ladygo.auth.web.dto.DriverPasswordForgotRequest;
import com.ladygo.auth.web.dto.DriverPasswordLoginRequest;
import com.ladygo.auth.web.dto.DriverPasswordResetRequest;
import com.ladygo.auth.web.dto.DriverPasswordSetRequest;
import com.ladygo.auth.web.dto.DriverPasswordStatusResponse;
import com.ladygo.auth.web.dto.TokenResponse;
import com.ladygo.common.api.ApiResponse;
import com.ladygo.security.jwt.AuthPrincipal;
import com.ladygo.security.net.ClientIpResolver;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Captain (DRIVER) password sign-in for the website and the Android driver app. OTP sign-in in {@link AuthController}
 * is unchanged. Passwords only ever travel in request bodies and never appear in a response or log.
 */
@RestController
@RequestMapping("/api/v1/auth/driver")
public class DriverAuthController {

    private static final String FORGOT_REPLY = "If that account exists, we have emailed a link to choose a new password.";

    private final DriverPasswordService service;
    private final ClientIpResolver clientIpResolver;

    public DriverAuthController(DriverPasswordService service, ClientIpResolver clientIpResolver) {
        this.service = service;
        this.clientIpResolver = clientIpResolver;
    }

    /** Public. One generic 401 for a wrong identifier, wrong password or an account without a password. */
    @Operation(operationId = "driverPasswordLogin")
    @PostMapping("/password/login")
    public ApiResponse<TokenResponse> login(
            @Valid @RequestBody DriverPasswordLoginRequest request, HttpServletRequest httpRequest) {
        TokenPair pair = service.login(
                request.identifier(), request.password(), request.deviceId(), clientIpResolver.resolve(httpRequest));
        return ApiResponse.ok(new TokenResponse(pair.accessToken(), pair.refreshToken()));
    }

    @Operation(operationId = "driverPasswordStatus")
    @GetMapping("/password/status")
    @PreAuthorize("hasRole('DRIVER')")
    public ApiResponse<DriverPasswordStatusResponse> status(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(new DriverPasswordStatusResponse(service.hasPassword(principal.userId())));
    }

    @Operation(operationId = "driverPasswordSet")
    @PostMapping("/password/set")
    @PreAuthorize("hasRole('DRIVER')")
    public ApiResponse<Void> set(
            @Valid @RequestBody DriverPasswordSetRequest request, @AuthenticationPrincipal AuthPrincipal principal,
            HttpServletRequest httpRequest) {
        service.setFirstPassword(principal.userId(), request.newPassword(), clientIpResolver.resolve(httpRequest));
        return ApiResponse.ok(null);
    }

    @Operation(operationId = "driverPasswordChange")
    @PostMapping("/password/change")
    @PreAuthorize("hasRole('DRIVER')")
    public ApiResponse<TokenResponse> change(
            @Valid @RequestBody DriverPasswordChangeRequest request, @AuthenticationPrincipal AuthPrincipal principal,
            HttpServletRequest httpRequest) {
        TokenPair pair = service.changePassword(principal.userId(), request.currentPassword(), request.newPassword(),
                null, clientIpResolver.resolve(httpRequest));
        return ApiResponse.ok(new TokenResponse(pair.accessToken(), pair.refreshToken()));
    }

    /** Public. Always the same reply, whether or not the account exists. */
    @Operation(operationId = "driverPasswordForgot")
    @PostMapping("/password/forgot")
    public ApiResponse<com.ladygo.auth.web.dto.MessageResponse> forgot(
            @Valid @RequestBody DriverPasswordForgotRequest request, HttpServletRequest httpRequest) {
        service.requestReset(request.identifier(), clientIpResolver.resolve(httpRequest));
        return ApiResponse.ok(new com.ladygo.auth.web.dto.MessageResponse(FORGOT_REPLY));
    }

    /** Public: the emailed single-use token is the credential. */
    @Operation(operationId = "driverPasswordReset")
    @PostMapping("/password/reset")
    public ApiResponse<Void> reset(
            @Valid @RequestBody DriverPasswordResetRequest request, HttpServletRequest httpRequest) {
        service.resetPassword(request.token(), request.newPassword(), clientIpResolver.resolve(httpRequest));
        return ApiResponse.ok(null);
    }
}
