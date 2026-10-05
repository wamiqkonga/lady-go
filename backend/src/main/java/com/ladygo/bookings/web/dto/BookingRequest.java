package com.ladygo.bookings.web.dto;

import com.ladygo.payments.domain.PaymentMode;
import com.ladygo.vehicles.domain.VehicleType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;

public record BookingRequest(
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double pickupLatitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double pickupLongitude,
        @NotBlank String pickupAddress,
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double destinationLatitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double destinationLongitude,
        @NotBlank String destinationAddress,
        @NotNull VehicleType vehicleType,
        @NotNull PaymentMode paymentMethod,
        Instant scheduledAt,
        @NotNull @Positive Long expectedFare) {
}
