package com.ladygo.admin.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record PricingSampleRequest(
        @Min(0) @Max(500_000) int distanceMeters, @Min(0) @Max(86_400) int durationSeconds) {
}
