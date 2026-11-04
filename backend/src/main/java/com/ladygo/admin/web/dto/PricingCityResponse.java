package com.ladygo.admin.web.dto;

import java.util.List;
import java.util.UUID;
import com.ladygo.vehicles.domain.VehicleType;

public record PricingCityResponse(
        UUID id, String name, boolean active, boolean hasServiceArea, List<VehicleType> vehicleTypesWithFare) {
}
