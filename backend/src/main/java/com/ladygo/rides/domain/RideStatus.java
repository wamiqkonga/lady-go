package com.ladygo.rides.domain;

/** Persisted by name; values must match the CHECK constraint in the Flyway migrations (see EnumSchemaParityTest). */
public enum RideStatus {
    ASSIGNED,
    STARTED,
    COMPLETED,
    CANCELLED
}
