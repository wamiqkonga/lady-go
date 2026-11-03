package com.ladygo.auth.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DriverPasswordForgotRequest(@NotBlank @Size(max = 254) String identifier) {
}
