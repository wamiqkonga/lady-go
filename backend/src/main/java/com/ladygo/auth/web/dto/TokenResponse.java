package com.ladygo.auth.web.dto;

public record TokenResponse(String accessToken, String refreshToken) {
}
