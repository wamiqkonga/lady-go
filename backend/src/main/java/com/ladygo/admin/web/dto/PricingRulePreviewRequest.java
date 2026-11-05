package com.ladygo.admin.web.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.ladygo.vehicles.domain.VehicleType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Proposed values (whole paise) to price sample trips with. {@code samples} may be omitted for the default three. */
public record PricingRulePreviewRequest(
        @NotNull UUID cityId, @NotNull VehicleType vehicleType,
        @NotNull @JsonDeserialize(using = WholePaiseDeserializer.class) Long baseFare,
        @NotNull @JsonDeserialize(using = WholePaiseDeserializer.class) Long perKm,
        @NotNull @JsonDeserialize(using = WholePaiseDeserializer.class) Long perMinute,
        @NotNull @JsonDeserialize(using = WholePaiseDeserializer.class) Long minimumFare,
        @NotNull @JsonDeserialize(using = WholePaiseDeserializer.class) Long cancellationFee,
        @NotNull BigDecimal serviceFeePct,
        @NotNull @JsonDeserialize(using = WholePaiseDeserializer.class) Long serviceFeeFlat,
        @Valid @Size(max = 10) List<PricingSampleRequest> samples) {
}
