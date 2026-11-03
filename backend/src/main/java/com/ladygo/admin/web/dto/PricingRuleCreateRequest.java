package com.ladygo.admin.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.ladygo.vehicles.domain.VehicleType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A new fare version. Money is whole paise. {@code effectiveFrom} null means now, otherwise it must not be in the past.
 * {@code expectedLatestRuleId} is the {@code latestRuleId} the admin saw (null when no fare existed); a mismatch means
 * someone else changed the fare meanwhile and nothing is written.
 */
public record PricingRuleCreateRequest(
        @NotNull UUID cityId, @NotNull VehicleType vehicleType,
        @NotNull @JsonDeserialize(using = WholePaiseDeserializer.class) Long baseFare,
        @NotNull @JsonDeserialize(using = WholePaiseDeserializer.class) Long perKm,
        @NotNull @JsonDeserialize(using = WholePaiseDeserializer.class) Long perMinute,
        @NotNull @JsonDeserialize(using = WholePaiseDeserializer.class) Long minimumFare,
        @NotNull @JsonDeserialize(using = WholePaiseDeserializer.class) Long cancellationFee,
        @NotNull BigDecimal serviceFeePct,
        @NotNull @JsonDeserialize(using = WholePaiseDeserializer.class) Long serviceFeeFlat,
        Instant effectiveFrom, UUID expectedLatestRuleId,
        @NotBlank @Size(max = 500) String reason, boolean confirmLargeChange) {
}
