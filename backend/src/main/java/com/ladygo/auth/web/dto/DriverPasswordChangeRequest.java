package com.ladygo.auth.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DriverPasswordChangeRequest(
        @NotBlank @Size(max = 128) String currentPassword,
        @NotBlank @Size(max = 1000) String newPassword) {
}
