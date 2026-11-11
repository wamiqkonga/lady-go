package com.ladygo.locations.web.dto;

import java.util.List;
import java.util.UUID;
import com.ladygo.vehicles.domain.VehicleType;

/** {@code vehicleTypesWithFare}: vehicle types with a fare in force today - the only ones a rider can get an estimate for. */
public record CityResponse(UUID id, String name, String country, List<VehicleType> vehicleTypesWithFare) {
}
