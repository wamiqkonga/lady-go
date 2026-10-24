package com.ladygo.admin.web.dto;

import java.util.UUID;
import com.ladygo.vehicles.domain.VehicleType;
import com.ladygo.vehicles.domain.VehicleVerificationStatus;

public record VehicleAdminResponse(
        UUID id, String registrationNumber, VehicleType vehicleType, VehicleVerificationStatus verificationStatus) {
}
