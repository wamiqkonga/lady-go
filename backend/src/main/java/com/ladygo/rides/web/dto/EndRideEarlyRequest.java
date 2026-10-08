package com.ladygo.rides.web.dto;

import jakarta.validation.constraints.NotBlank;

public record EndRideEarlyRequest(@NotBlank String reason) {
}
