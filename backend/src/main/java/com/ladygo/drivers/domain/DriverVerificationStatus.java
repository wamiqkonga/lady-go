package com.ladygo.drivers.domain;

/** Persisted by name; values must match the CHECK constraint in the Flyway migrations (see EnumSchemaParityTest). */
public enum DriverVerificationStatus {
    NOT_SUBMITTED,
    PENDING_REVIEW,
    APPROVED,
    REJECTED,
    SUSPENDED
}
