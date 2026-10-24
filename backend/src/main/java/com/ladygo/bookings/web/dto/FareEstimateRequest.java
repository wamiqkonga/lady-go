package com.ladygo.bookings.web.dto;

import com.ladygo.vehicles.domain.VehicleType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

public record FareEstimateRequest(
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double pickupLatitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double pickupLongitude,
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double destinationLatitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double destinationLongitude,
        @NotNull VehicleType vehicleType) {
}
