package com.ladygo.notifications.web.dto;

import com.ladygo.notifications.domain.DevicePlatform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The app is derived from the caller's role, not the request, so a rider token can never register as a driver device. */
public record RegisterDeviceRequest(@NotBlank @Size(max = 500) String token, @NotNull DevicePlatform platform) {
}
