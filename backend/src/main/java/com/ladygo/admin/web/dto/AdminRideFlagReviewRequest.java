package com.ladygo.admin.web.dto;

import jakarta.validation.constraints.NotBlank;

/** A review always requires a note - see AdminRideOversightService.reviewFlag's own javadoc for why. */
public record AdminRideFlagReviewRequest(@NotBlank String note) {
}
