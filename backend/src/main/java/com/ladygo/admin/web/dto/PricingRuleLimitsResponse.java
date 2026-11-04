package com.ladygo.admin.web.dto;

import java.math.BigDecimal;

/** Absolute per-field limits and how many days ahead a fare may be scheduled, read from the {@code configuration} table (whole paise, percent for the fee). */
public record PricingRuleLimitsResponse(
        long baseFareMaxPaise, long perKmMaxPaise, long perMinuteMaxPaise, long minimumFareMaxPaise,
        long cancellationFeeMaxPaise, long serviceFeeFlatMaxPaise, BigDecimal serviceFeePctMax,
        int scheduleMaxDays) {
}
