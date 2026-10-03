package com.ladygo.vehicles.domain;

/** Persisted by name; values must match the CHECK constraint in the Flyway migrations (see EnumSchemaParityTest). */
public enum VehicleVerificationStatus {
    PENDING,
    APPROVED,
    REJECTED,
    SUSPENDED
}
