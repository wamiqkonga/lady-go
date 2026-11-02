package com.ladygo.auth.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code identifier} is the captain's phone number or email; the server works out which. */
public record DriverPasswordLoginRequest(
        @NotBlank @Size(max = 254) String identifier,
        @NotBlank @Size(max = 128) String password,
        @Size(max = 100) String deviceId) {
}
