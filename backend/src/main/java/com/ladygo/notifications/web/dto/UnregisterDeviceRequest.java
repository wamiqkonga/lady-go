package com.ladygo.notifications.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UnregisterDeviceRequest(@NotBlank @Size(max = 500) String token) {
}
