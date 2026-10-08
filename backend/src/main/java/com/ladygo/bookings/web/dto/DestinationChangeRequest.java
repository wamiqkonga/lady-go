package com.ladygo.bookings.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** docs/api/ride-lifecycle.md section 13b-3 - the confirm step; {@code expectedFare} is the rider's
 *  confirmation of the fresh quote from the estimate step (same "rider confirms the new fare" shape
 *  as {@code BookingRequest}). Pickup is never re-specified - it comes from the existing booking. */
public record DestinationChangeRequest(
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double destinationLatitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double destinationLongitude,
        @NotBlank String destinationAddress,
        @NotNull Long expectedFare) {
}
