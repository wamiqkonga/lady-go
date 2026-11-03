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
        UUID pricingRuleId,
        /** The route to draw on the fare screen, as a Google encoded polyline. {@code null} when there is no real
         *  route (the maps provider was unreachable and the fare was approximated); the app then draws a straight line. */
        String routePolyline) {
}
