package com.ladygo.ratings.web.dto;

import java.util.List;
import com.ladygo.ratings.domain.RatingTag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * {@code score} 1-5. {@code comment} is optional, trimmed, at most 500 characters after trimming (checked in
 * the service) and never shown to the rated person or logged. {@code tags} are optional, at most 5, from the
 * fixed {@link RatingTag} list; the rated person is always derived server side.
 */
public record SubmitRatingRequest(
        @NotNull @Min(1) @Max(5) Integer score, String comment, List<RatingTag> tags) {
}
