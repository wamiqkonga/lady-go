package com.ladygo.support.web.dto;

import java.util.UUID;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** docs/api/safety.md section 1. {@code bookingId} is optional context; it must be the caller's own booking. */
public record SosRequest(
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
        UUID bookingId,
        @Size(max = 500) String notes) {
}
