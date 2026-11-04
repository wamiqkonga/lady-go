package com.ladygo.auth.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** The driver app's code check: like {@code /auth/otp/verify} for a driver, but refuses drivers who are not approved. */
public record DriverAppVerifyRequest(
        @NotNull @Pattern(regexp = "^\\+[1-9][0-9]{7,14}$") String phone,
        @NotBlank @Pattern(regexp = "^[0-9]{6}$") String code,
        @Size(max = 100) String deviceId) {
}
