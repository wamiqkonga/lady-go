package com.ladygo.ratings.domain;

/** Persisted by name; values must match the CHECK constraint in the Flyway migrations (see EnumSchemaParityTest). */
public enum RaterRole {
    CUSTOMER,
    DRIVER
}
