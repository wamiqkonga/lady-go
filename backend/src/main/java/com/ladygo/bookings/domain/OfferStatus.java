package com.ladygo.bookings.domain;

/** Persisted by name; values must match the CHECK constraint in the Flyway migrations (see EnumSchemaParityTest). */
public enum OfferStatus {
    OFFERED,
    ACCEPTED,
    REJECTED,
    EXPIRED,
    CANCELLED
}
