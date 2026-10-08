package com.ladygo.rides.domain;

/** Persisted by name; values must match the CHECK constraint in the Flyway migrations (see EnumSchemaParityTest). */
public enum RideFlagType {
    EARLY_END,
    COMPLETION_FAR_FROM_DESTINATION,
    PIN_LOCKOUT,
    CASH_DISPUTE,
    RIDER_SAFETY_CANCEL,
    RIDER_CONFIRMED_ARRIVAL
}
