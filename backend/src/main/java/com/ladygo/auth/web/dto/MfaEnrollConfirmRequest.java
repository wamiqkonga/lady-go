package com.ladygo.auth.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record MfaEnrollConfirmRequest(@NotBlank @Pattern(regexp = "^[0-9]{6}$") String code) {
}
