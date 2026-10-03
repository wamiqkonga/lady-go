package com.ladygo.users.domain;

/** Persisted by name; values must match the CHECK constraint in the Flyway migrations (see EnumSchemaParityTest). */
public enum UserRole {
    CUSTOMER,
    DRIVER,
    ADMIN,
    SUPER_ADMIN,
    SUPPORT
}
