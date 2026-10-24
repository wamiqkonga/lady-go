package com.ladygo.ratings.web.dto;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Aggregates only (docs/api/driver-earnings-ratings.md section 4.3): comments and individual riders'
 * scores are never exposed. {@code distribution} has keys "1" to "5". {@code recentTagCounts} counts tags
 * over the driver's 10 most recent ratings.
 */
public record DriverRatingSummaryResponse(
        BigDecimal avg, int count, Map<String, Long> distribution, Map<String, Long> recentTagCounts) {
}
