package com.ladygo.rides.web.dto;

import jakarta.validation.constraints.NotBlank;

public record DisputeCashRequest(@NotBlank String reason) {
}
