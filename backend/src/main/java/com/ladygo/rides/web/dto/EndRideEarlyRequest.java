package com.ladygo.rides.web.dto;

import jakarta.validation.constraints.NotBlank;

/** docs/api/ride-lifecycle.md section 13c; absent/false if omitted. {@code safetyConcern}: on the rider
 *  endpoint it waives the no-progress fee (capped) and raises a safety-cancel flag; on the driver endpoint
 *  (always fee-waived regardless) it opens exactly one HIGH REPORT_CUSTOMER safety incident for ops
 *  (docs/api/driver-earnings-ratings.md section 6, B5) - repeats do not create a second one. */
public record EndRideEarlyRequest(@NotBlank String reason, boolean safetyConcern) {
}
