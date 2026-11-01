package com.ladygo.auth.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** {@code totpCode} is required only when the account has an authenticator enrolled; the server decides, not the client. */
public record AdminPasswordResetRequest(
        @NotBlank @Size(max = 200) String token,
        @NotBlank @Size(min = 12, max = 128) String newPassword,
        @Pattern(regexp = "^[0-9]{6}$") String totpCode) {
}
