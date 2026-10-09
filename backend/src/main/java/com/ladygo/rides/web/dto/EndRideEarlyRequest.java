package com.ladygo.rides.web.dto;

import jakarta.validation.constraints.NotBlank;

/** docs/api/ride-lifecycle.md section 13c - {@code safetyConcern} is rider-only (ignored on the
 *  driver-side endpoint, which is always fee-waived regardless); absent/false if omitted. */
public record EndRideEarlyRequest(@NotBlank String reason, boolean safetyConcern) {
}
