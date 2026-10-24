package com.ladygo.bookings.web.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record DriverSummaryResponse(
        UUID driverId,
        String name,
        String profilePhotoKey,
        BigDecimal ratingAvg,
        String vehicleMake,
        String vehicleModel,
        String vehicleColor,
        String vehicleRegistration) {
}
