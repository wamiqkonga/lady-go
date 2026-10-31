package com.ladygo.auth.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminPasswordForgotRequest(@NotBlank @Email @Size(max = 254) String email) {
}
