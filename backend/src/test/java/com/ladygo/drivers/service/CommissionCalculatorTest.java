package com.ladygo.drivers.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CommissionCalculatorTest {

    @Test
    void unsetRateMeansNoCommission() {
        assertThat(CommissionCalculator.commission(12_345, null)).isZero();
    }

    @Test
    void zeroRateOrZeroFareMeansNoCommission() {
        assertThat(CommissionCalculator.commission(12_345, 0)).isZero();
        assertThat(CommissionCalculator.commission(0, 1500)).isZero();
    }

    @Test
    void exactMultiplesAreExact() {
        assertThat(CommissionCalculator.commission(10_000, 1500)).isEqualTo(1500);
        assertThat(CommissionCalculator.commission(20_000, 1000)).isEqualTo(2000);
    }

    @Test
    void oddPaiseRoundHalfUp() {
        // 333 * 15% = 49.95 -> 50
        assertThat(CommissionCalculator.commission(333, 1500)).isEqualTo(50);
        // 10 * 5% = 0.5 -> exactly half rounds UP to 1
        assertThat(CommissionCalculator.commission(10, 500)).isEqualTo(1);
        // 9 * 5% = 0.45 -> 0
        assertThat(CommissionCalculator.commission(9, 500)).isZero();
        // 10_001 * 15% = 1500.15 -> 1500
        assertThat(CommissionCalculator.commission(10_001, 1500)).isEqualTo(1500);
        // 1 paise at 50.00% = 0.5 -> 1
        assertThat(CommissionCalculator.commission(1, 5000)).isEqualTo(1);
    }

    @Test
    void fullRateTakesEverythingAndNetNeverGoesNegative() {
        assertThat(CommissionCalculator.commission(777, 10_000)).isEqualTo(777);
    }

    @Test
    void commissionNeverExceedsGrossForAnyFareAndRate() {
        for (long gross : new long[] {0, 1, 2, 3, 99, 100, 101, 12_345, 1_000_001}) {
            for (int bps : new int[] {0, 1, 49, 50, 51, 1500, 9999, 10_000}) {
                long c = CommissionCalculator.commission(gross, bps);
                assertThat(c).isBetween(0L, gross);
            }
        }
    }

    @Test
    void hugeFaresDoNotOverflowSilently() {
        assertThatThrownBy(() -> CommissionCalculator.commission(Long.MAX_VALUE, 10_000))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> CommissionCalculator.commission(-1, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CommissionCalculator.commission(100, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CommissionCalculator.commission(100, 10_001)).isInstanceOf(IllegalArgumentException.class);
    }
}
