package com.ladygo.auth.web.dto;

import jakarta.validation.constraints.NotBlank;

public record LogoutRequest(@NotBlank String refreshToken, boolean logoutAll) {
}
