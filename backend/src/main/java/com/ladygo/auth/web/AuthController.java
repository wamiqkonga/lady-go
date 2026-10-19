package com.ladygo.auth.web;

import com.ladygo.auth.service.AccessTokenIssuer;
import com.ladygo.auth.service.OtpLoginService;
import com.ladygo.auth.service.RefreshTokenService;
import com.ladygo.auth.service.TokenPair;
import com.ladygo.auth.web.dto.LogoutRequest;
import com.ladygo.auth.web.dto.MessageResponse;
import com.ladygo.auth.web.dto.OtpRequestRequest;
import com.ladygo.auth.web.dto.OtpVerifyRequest;
import com.ladygo.auth.web.dto.RefreshRequest;
import com.ladygo.auth.web.dto.TokenResponse;
import com.ladygo.common.api.ApiResponse;
import com.ladygo.common.exception.UnauthorizedException;
import com.ladygo.security.jwt.AuthPrincipal;
import com.ladygo.security.net.ClientIpResolver;
import com.ladygo.users.domain.User;
import com.ladygo.users.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Optional;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CUSTOMER/DRIVER OTP login, token refresh and logout (docs/security/auth-design.md). Admin
 * login/enrolment lives in {@link AdminAuthController}.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String GENERIC_OTP_SENT = "If this number is valid, a code was sent.";
    private static final String PHONE_VERIFIED = "Phone verified.";

    private final OtpLoginService otpLoginService;
    private final RefreshTokenService refreshTokenService;
    private final AccessTokenIssuer accessTokenIssuer;
    private final UserRepository users;
    private final ClientIpResolver clientIpResolver;

    public AuthController(
            OtpLoginService otpLoginService,
            RefreshTokenService refreshTokenService,
            AccessTokenIssuer accessTokenIssuer,
            UserRepository users,
            ClientIpResolver clientIpResolver) {
        this.otpLoginService = otpLoginService;
        this.refreshTokenService = refreshTokenService;
        this.accessTokenIssuer = accessTokenIssuer;
        this.users = users;
        this.clientIpResolver = clientIpResolver;
    }

    @PostMapping("/otp/request")
    public ApiResponse<MessageResponse> requestOtp(
            @Valid @RequestBody OtpRequestRequest request, HttpServletRequest httpRequest) {
        otpLoginService.requestOtp(
                request.phone(), request.purpose(), request.intendedRole(),
                clientIpResolver.resolve(httpRequest), request.challengeToken(), request.email());
        return ApiResponse.ok(new MessageResponse(GENERIC_OTP_SENT));
    }

    @PostMapping("/otp/verify")
    public ApiResponse<Object> verifyOtp(@Valid @RequestBody OtpVerifyRequest request) {
        Optional<TokenPair> tokens = otpLoginService.verifyOtp(
                request.phone(), request.code(), request.purpose(), request.intendedRole(), request.deviceId());
        if (tokens.isPresent()) {
            TokenPair pair = tokens.get();
            return ApiResponse.ok(new TokenResponse(pair.accessToken(), pair.refreshToken()));
        }
        return ApiResponse.ok(new MessageResponse(PHONE_VERIFIED));
    }

    @PostMapping("/refresh")
    public ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        var rotated = refreshTokenService.rotate(request.refreshToken());
        User user = users.findById(rotated.userId()).orElseThrow(UnauthorizedException::new);
        String access = accessTokenIssuer.issue(user.getId(), user.getRole());
        return ApiResponse.ok(new TokenResponse(access, rotated.rawRefreshToken()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(
            @Valid @RequestBody LogoutRequest request, @AuthenticationPrincipal AuthPrincipal principal) {
        if (request.logoutAll() && principal != null && !principal.enrolment()) {
            refreshTokenService.revokeAll(principal.userId());
        } else {
            refreshTokenService.revoke(request.refreshToken());
        }
        return ApiResponse.ok(null);
    }
}
