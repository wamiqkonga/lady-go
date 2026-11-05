package com.ladygo.admin.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import com.ladygo.vehicles.domain.VehicleType;

/** One fare version. Money fields are whole paise. {@code cancellable} is only ever true for a SCHEDULED version no booking refers to. */
public record PricingRuleResponse(
        UUID id, UUID cityId, VehicleType vehicleType, String currency,
        long baseFare, long perKm, long perMinute, long minimumFare, long cancellationFee,
        BigDecimal serviceFeePct, long serviceFeeFlat,
        Instant effectiveFrom, Instant effectiveTo, UUID createdBy, Instant createdAt,
        Status status, boolean cancellable) {

    public enum Status { IN_FORCE, SCHEDULED, ENDED }
}
