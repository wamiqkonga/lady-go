package com.ladygo.drivers.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record DriverLocationRequest(
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
        @NotNull Instant recordedAt,
        /* Deliberately not @NotNull: mandatory in the API contract (docs/api/ride-lifecycle.md
         * section 13b-2) and documented as required below, but enforced in
         * DriverLocationService so a dev-profile-only bypass can apply - a jakarta-validation
         * @NotNull would reject the request before that bypass ever runs. */
        @Schema(description = "Device-reported GPS accuracy in metres. Required outside the dev profile.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Double accuracyMeters) {
}
