package com.ladygo.common.domain;

/** Persisted by name; values must match the CHECK constraint in the Flyway migrations (see EnumSchemaParityTest). */
public enum ActorType {
    CUSTOMER,
    DRIVER,
    ADMIN,
    SYSTEM
}
