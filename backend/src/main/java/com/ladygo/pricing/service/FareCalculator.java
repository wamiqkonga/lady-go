package com.ladygo.pricing.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import com.ladygo.pricing.domain.PricingRule;
import org.springframework.stereotype.Service;

/**
 * Pure fare calculation from a {@link PricingRule} and a distance/duration (docs/api/booking.md
 * section 1). Money in minor units throughout (ADR-007) - no floating-point fare is ever returned.
 */
@Service
public class FareCalculator {

    public FareResult calculate(PricingRule rule, int distanceMeters, int durationSeconds) {
        long distanceFare = Math.round(rule.getPerKm() * (distanceMeters / 1000.0));
        long timeFare = Math.round(rule.getPerMinute() * (durationSeconds / 60.0));
        long subtotal = rule.getBaseFare() + distanceFare + timeFare;
        long flooredSubtotal = Math.max(subtotal, rule.getMinimumFare());
        // Any minimum-fare top-up is absorbed into baseFare so the breakdown still sums to the total.
        long adjustedBaseFare = rule.getBaseFare() + (flooredSubtotal - subtotal);

        long serviceFee = BigDecimal.valueOf(flooredSubtotal)
                .multiply(rule.getServiceFeePct())
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                .longValueExact()
                + rule.getServiceFeeFlat();

        long total = flooredSubtotal + serviceFee;
        return new FareResult(total, new FareBreakdown(adjustedBaseFare, distanceFare, timeFare, serviceFee));
    }
}
