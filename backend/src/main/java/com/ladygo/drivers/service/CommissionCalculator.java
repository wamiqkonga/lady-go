package com.ladygo.drivers.service;

/**
 * Commission maths in integer arithmetic only (money is minor units, ADR-007; never floating point).
 * {@code commission = round_half_up(gross * bps / 10000)}, {@code net = gross - commission}
 * (docs/api/driver-earnings-ratings.md section 5).
 */
public final class CommissionCalculator {

    public static final int MAX_BPS = 10_000;

    private CommissionCalculator() {
    }

    /** @param bps basis points, 0..10000; {@code null} means "not configured" and yields no commission. */
    public static long commission(long gross, Integer bps) {
        if (gross < 0) {
            throw new IllegalArgumentException("gross must not be negative");
        }
        if (bps == null || bps == 0) {
            return 0L;
        }
        if (bps < 0 || bps > MAX_BPS) {
            throw new IllegalArgumentException("bps out of range");
        }
        // gross, bps >= 0 here, so adding half the divisor before the truncating division is round-half-up.
        return Math.addExact(Math.multiplyExact(gross, (long) bps), MAX_BPS / 2) / MAX_BPS;
    }
}
