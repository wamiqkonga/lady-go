package com.ladygo.rides.web.dto;

import com.ladygo.rides.domain.RideFlagRiderResponse;
import jakarta.validation.constraints.NotNull;

public record RideFlagRespondRequest(@NotNull RideFlagRiderResponse action) {
}
