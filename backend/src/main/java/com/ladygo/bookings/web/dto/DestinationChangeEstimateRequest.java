package com.ladygo.bookings.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/** docs/api/ride-lifecycle.md section 13b-3 - the no-mutation estimate step. Pickup is never
 *  re-specified here - it comes from the existing booking, not from client input. */
public record DestinationChangeEstimateRequest(
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double destinationLatitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double destinationLongitude) {
}
