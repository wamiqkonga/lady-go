package com.ladygo.bookings.web.dto;

import com.ladygo.pricing.service.FareBreakdown;
import com.ladygo.vehicles.domain.VehicleType;
import java.util.UUID;

public record FareEstimateResponse(
        UUID cityId,
        VehicleType vehicleType,
        String currency,
        int distanceMeters,
        int durationSeconds,
        boolean approximate,
        long estimatedFare,
        FareBreakdown breakdown,
        UUID pricingRuleId) {
}
