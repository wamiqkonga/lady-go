package com.ladygo.auth.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DriverPasswordResetRequest(
        @NotBlank @Size(max = 200) String token,
        @NotBlank @Size(max = 1000) String newPassword) {
}
