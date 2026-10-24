package com.ladygo.pricing.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import com.ladygo.pricing.domain.PricingRule;
import org.junit.jupiter.api.Test;

class FareCalculatorTest {

    private final FareCalculator calculator = new FareCalculator();

    private PricingRule rule(long baseFare, long perKm, long perMinute, long minimumFare,
            long serviceFeeFlat, String serviceFeePct) {
        PricingRule rule = new PricingRule();
        rule.setBaseFare(baseFare);
        rule.setPerKm(perKm);
        rule.setPerMinute(perMinute);
        rule.setMinimumFare(minimumFare);
        rule.setServiceFeeFlat(serviceFeeFlat);
        rule.setServiceFeePct(new BigDecimal(serviceFeePct));
        return rule;
    }

    @Test
    void breakdownSumsExactlyToTheTotal() {
        PricingRule rule = rule(5000, 1200, 200, 8000, 0, "5.00");
        FareResult result = calculator.calculate(rule, 7400, 1140);

        long sum = result.breakdown().baseFare() + result.breakdown().distanceFare()
                + result.breakdown().timeFare() + result.breakdown().serviceFee();
        assertThat(sum).isEqualTo(result.totalFare());
    }

    @Test
    void distanceAndTimeFareComputedFromTheRule() {
        PricingRule rule = rule(5000, 1200, 200, 0, 0, "0");
        // 7.4km * 1200 = 8880; 19 minutes * 200 = 3800
        FareResult result = calculator.calculate(rule, 7400, 1140);

        assertThat(result.breakdown().distanceFare()).isEqualTo(8880);
        assertThat(result.breakdown().timeFare()).isEqualTo(3800);
        assertThat(result.totalFare()).isEqualTo(5000 + 8880 + 3800);
    }

    @Test
    void minimumFareFloorIsAppliedAndAbsorbedIntoBaseFare() {
        // base+distance+time = 5000 + 120 + 20 = 5140, below the 8000 minimum.
        PricingRule rule = rule(5000, 1200, 200, 8000, 0, "0");
        FareResult result = calculator.calculate(rule, 100, 6);

        assertThat(result.totalFare()).isEqualTo(8000);
        assertThat(result.breakdown().baseFare()).isEqualTo(5000 + (8000 - 5140));
        long sum = result.breakdown().baseFare() + result.breakdown().distanceFare()
                + result.breakdown().timeFare() + result.breakdown().serviceFee();
        assertThat(sum).isEqualTo(8000);
    }

    @Test
    void serviceFeePctAndFlatAreBothApplied() {
        PricingRule rule = rule(5000, 0, 0, 0, 100, "10.00");
        FareResult result = calculator.calculate(rule, 0, 0);

        // 10% of 5000 = 500, plus flat 100 = 600.
        assertThat(result.breakdown().serviceFee()).isEqualTo(600);
        assertThat(result.totalFare()).isEqualTo(5600);
    }

    @Test
    void zeroDistanceAndDurationStillChargesBaseFare() {
        PricingRule rule = rule(5000, 1200, 200, 0, 0, "0");
        FareResult result = calculator.calculate(rule, 0, 0);

        assertThat(result.totalFare()).isEqualTo(5000);
    }
}
