package com.ladygo.bookings.service;

public record EligibilityDecision(boolean eligible, String reasonIfIneligible) {

    public static EligibilityDecision allowed() {
        return new EligibilityDecision(true, null);
    }

    public static EligibilityDecision denied(String reason) {
        return new EligibilityDecision(false, reason);
    }
}
