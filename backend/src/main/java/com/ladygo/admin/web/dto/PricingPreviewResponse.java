package com.ladygo.admin.web.dto;

import java.util.List;

/** Sample fares from the real {@code FareCalculator}: {@code current} is null when no fare is in force yet. */
public record PricingPreviewResponse(List<Sample> samples, List<LargeChange> largeChanges) {

    public record Sample(int distanceMeters, int durationSeconds, FareQuote current, FareQuote proposed) {
    }

    public record FareQuote(long totalFare, long baseFare, long distanceFare, long timeFare, long serviceFee) {
    }

    /** A field that moves by more than the configured percent; values are whole paise (or percent for the fee). */
    public record LargeChange(String field, String oldValue, String newValue) {
    }
}
