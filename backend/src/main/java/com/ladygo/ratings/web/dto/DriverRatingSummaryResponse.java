package com.ladygo.ratings.web.dto;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Aggregates only (docs/api/driver-earnings-ratings.md section 4.3): comments and individual riders'
 * scores are never exposed. {@code avg} and {@code count} are always present. {@code distribution} (keys
 * "1" to "5") and {@code recentTagCounts} (tags over the driver's 10 most recent ratings) are only filled
 * once {@code count} is at least 5; below that {@code distribution} is all zeros and
 * {@code recentTagCounts} is empty, so one rider's score or tags cannot be inferred (retaliation risk).
 */
public record DriverRatingSummaryResponse(
        BigDecimal avg, int count, Map<String, Long> distribution, Map<String, Long> recentTagCounts) {
}
