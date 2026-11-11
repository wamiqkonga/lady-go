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
        String vehicleRegistration,
        /** Captain's real phone for the rider's "Call captain" button; non-null only from acceptance until the
         *  ride ends (BookingField.COUNTERPART_PHONE). Never set for the driver's own view. */
        String phone) {
}
