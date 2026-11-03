package com.ladygo.auth.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code identifier} is the email or the phone number (with country code) she registered with. */
public record CustomerLoginRequest(
        @NotBlank @Size(max = 254) String identifier,
        @NotBlank @Size(max = 72) String password,
        @Size(max = 100) String deviceId) {
}
