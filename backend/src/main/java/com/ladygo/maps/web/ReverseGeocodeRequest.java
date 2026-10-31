package com.ladygo.maps.web;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/** A point the rider or driver dropped on the map. Sent in the body, not the URL, so coordinates stay out of access logs. */
public record ReverseGeocodeRequest(
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude) {
}
