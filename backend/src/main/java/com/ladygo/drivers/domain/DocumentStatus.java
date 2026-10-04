package com.ladygo.drivers.domain;

/** Persisted by name; values must match the CHECK constraint in the Flyway migrations (see EnumSchemaParityTest). */
public enum DocumentStatus {
    PENDING,
    APPROVED,
    REJECTED
}
