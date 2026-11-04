package com.ladygo.notifications.web;

import com.ladygo.common.api.ApiResponse;
import com.ladygo.notifications.domain.AppType;
import com.ladygo.notifications.service.DeviceTokenService;
import com.ladygo.notifications.web.dto.RegisterDeviceRequest;
import com.ladygo.notifications.web.dto.UnregisterDeviceRequest;
import com.ladygo.security.jwt.AuthPrincipal;
import com.ladygo.users.domain.UserRole;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** docs/architecture/backend-architecture.md: {@code POST /notifications/devices} registers a push token. */
@RestController
@RequestMapping("/api/v1/notifications/devices")
@PreAuthorize("hasAnyRole('CUSTOMER', 'DRIVER')")
public class DeviceController {

    private final DeviceTokenService service;

    public DeviceController(DeviceTokenService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<Void> register(
            @AuthenticationPrincipal AuthPrincipal principal, @Valid @RequestBody RegisterDeviceRequest request) {
        service.register(principal.userId(), appFor(principal), request.platform(), request.token());
        return ApiResponse.ok(null);
    }

    @DeleteMapping
    public ApiResponse<Void> unregister(
            @AuthenticationPrincipal AuthPrincipal principal, @Valid @RequestBody UnregisterDeviceRequest request) {
        service.unregister(principal.userId(), request.token());
        return ApiResponse.ok(null);
    }

    private static AppType appFor(AuthPrincipal principal) {
        if (principal.role() == UserRole.CUSTOMER) {
            return AppType.CUSTOMER;
        }
        if (principal.role() == UserRole.DRIVER) {
            return AppType.DRIVER;
        }
        throw new IllegalStateException("Only riders and drivers have push devices.");
    }
}
