package com.ladygo.rides.domain;

/** Persisted by name; values must match the CHECK constraint in the Flyway migrations (see EnumSchemaParityTest). */
public enum RideFlagType {
    EARLY_END,
    COMPLETION_FAR_FROM_DESTINATION,
    PIN_LOCKOUT,
    CASH_DISPUTE,
    RIDER_SAFETY_CANCEL,
    RIDER_CONFIRMED_ARRIVAL,
    /** A rating of 1 or 2 (docs/api/driver-earnings-ratings.md section 4.3) - queued for admin review. */
    LOW_RATING,
    /** More than two 1-star ratings from one rider against the same driver within 7 days (section 7). */
    REPEATED_LOW_RATING
}
