package com.ladygo.payments.domain;

/** Persisted by name; values must match the CHECK constraint in the Flyway migrations (see EnumSchemaParityTest). */
public enum PaymentStatus {
    INITIATED,
    PENDING,
    SUCCEEDED,
    FAILED,
    REFUNDED,
    PARTIALLY_REFUNDED
}
