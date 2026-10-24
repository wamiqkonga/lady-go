package com.ladygo.drivers.web.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record DriverProfileRequest(@NotNull UUID cityId) {
}
