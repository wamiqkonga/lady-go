package com.ladygo.auth.web;

import com.ladygo.auth.service.CustomerAuthService;
import com.ladygo.auth.service.TokenPair;
import com.ladygo.auth.web.dto.CustomerLoginRequest;
import com.ladygo.auth.web.dto.CustomerRegisterRequest;
import com.ladygo.auth.web.dto.ForgotPasswordRequest;
import com.ladygo.auth.web.dto.MessageResponse;
import com.ladygo.auth.web.dto.RegisterOtpRequest;
import com.ladygo.auth.web.dto.ResetPasswordRequest;
import com.ladygo.auth.web.dto.TokenResponse;
import com.ladygo.common.api.ApiResponse;
import com.ladygo.security.net.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Rider sign-up, password login and password reset. All public: there is no session yet. */
@RestController
@RequestMapping("/api/v1/auth/customer")
public class CustomerAuthController {

    private static final String CODE_SENT = "We sent a code to your email.";
    private static final String RESET_REQUESTED = "If that email has an account, we sent a code to it.";
    private static final String PASSWORD_CHANGED = "Your password was changed. Please log in.";

    private final CustomerAuthService service;
    private final ClientIpResolver clientIpResolver;

    public CustomerAuthController(CustomerAuthService service, ClientIpResolver clientIpResolver) {
        this.service = service;
        this.clientIpResolver = clientIpResolver;
    }

    @PostMapping("/register/otp")
    public ApiResponse<MessageResponse> requestRegistrationOtp(
            @Valid @RequestBody RegisterOtpRequest request, HttpServletRequest http) {
        service.requestRegistrationOtp(request.phone(), request.email(), clientIpResolver.resolve(http));
        return ApiResponse.ok(new MessageResponse(CODE_SENT));
    }

    @PostMapping("/register")
    public ApiResponse<TokenResponse> register(@Valid @RequestBody CustomerRegisterRequest request) {
        TokenPair pair = service.register(
                request.phone(), request.email(), request.code(), request.password(), request.deviceId());
        return ApiResponse.ok(new TokenResponse(pair.accessToken(), pair.refreshToken()));
    }

    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(
            @Valid @RequestBody CustomerLoginRequest request, HttpServletRequest http) {
        TokenPair pair = service.login(
                request.identifier(), request.password(), request.deviceId(), clientIpResolver.resolve(http));
        return ApiResponse.ok(new TokenResponse(pair.accessToken(), pair.refreshToken()));
    }

    @PostMapping("/password/forgot")
    public ApiResponse<MessageResponse> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request, HttpServletRequest http) {
        service.requestPasswordReset(request.email(), clientIpResolver.resolve(http));
        return ApiResponse.ok(new MessageResponse(RESET_REQUESTED));
    }

    @PostMapping("/password/reset")
    public ApiResponse<MessageResponse> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        service.resetPassword(request.email(), request.code(), request.newPassword());
        return ApiResponse.ok(new MessageResponse(PASSWORD_CHANGED));
    }
}
