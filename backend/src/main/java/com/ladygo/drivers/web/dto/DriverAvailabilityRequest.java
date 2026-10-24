package com.ladygo.drivers.web.dto;

import jakarta.validation.constraints.NotNull;
import com.ladygo.drivers.domain.DriverAvailability;

public record DriverAvailabilityRequest(@NotNull DriverAvailability status) {
}
