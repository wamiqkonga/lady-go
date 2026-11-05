package com.ladygo.drivers.web.dto;

import jakarta.validation.constraints.NotNull;

/** The captain's full name as it appears on their licence or RC (trimmed and checked server-side). */
public record DriverNameRequest(@NotNull String fullName) {
}
