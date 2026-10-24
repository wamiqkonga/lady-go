package com.ladygo.bookings.web.dto;

import jakarta.validation.constraints.NotBlank;

public record CancelBookingRequest(@NotBlank String reason) {
}
