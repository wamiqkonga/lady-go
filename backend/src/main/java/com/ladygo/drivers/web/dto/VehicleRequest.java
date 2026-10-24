package com.ladygo.drivers.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import com.ladygo.vehicles.domain.VehicleType;

public record VehicleRequest(
        @NotBlank String registrationNumber,
        @NotNull VehicleType vehicleType,
        String make,
        String model,
        String color,
        Short manufactureYear) {
}
