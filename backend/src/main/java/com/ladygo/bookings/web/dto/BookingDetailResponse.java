package com.ladygo.bookings.web.dto;

import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.payments.domain.PaymentMode;
import com.ladygo.vehicles.domain.VehicleType;
import java.time.Instant;
import java.util.UUID;

public record BookingDetailResponse(
        UUID id,
        BookingStatus status,
        String pickupAddress,
        String destinationAddress,
        VehicleType vehicleType,
        PaymentMode paymentMethod,
        Long estimatedFare,
        Long finalFare,
        String currency,
        String ridePin,
        DriverSummaryResponse driver,
        Instant createdAt,
        Instant scheduledAt) {
}
