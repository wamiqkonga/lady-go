package com.ladygo.drivers.web.dto;

import java.util.UUID;
import com.ladygo.vehicles.domain.VehicleType;
import com.ladygo.vehicles.domain.VehicleVerificationStatus;

public record VehicleResponse(
        UUID id,
        String registrationNumber,
        VehicleType vehicleType,
        String make,
        String model,
        String color,
        Short manufactureYear,
        VehicleVerificationStatus verificationStatus) {
}
