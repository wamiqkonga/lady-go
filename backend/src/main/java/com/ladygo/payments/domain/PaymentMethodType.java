package com.ladygo.payments.domain;

/** Persisted by name; values must match the CHECK constraint in the Flyway migrations (see EnumSchemaParityTest). */
public enum PaymentMethodType {
    CARD,
    UPI,
    WALLET,
    OTHER
}
