package com.ladygo.ratings.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Echo of the stored rating, without the comment. */
public record RatingResponse(UUID bookingId, int score, List<String> tags, Instant createdAt) {
}
