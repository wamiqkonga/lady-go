package com.ladygo.auth.web.dto;

public record MfaEnrollResponse(String secret, String provisioningUri) {
}
