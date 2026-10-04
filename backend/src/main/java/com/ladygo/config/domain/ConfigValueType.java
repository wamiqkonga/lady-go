package com.ladygo.config.domain;

/** Persisted by name; values must match the CHECK constraint in the Flyway migrations (see EnumSchemaParityTest). */
public enum ConfigValueType {
    STRING,
    INTEGER,
    DECIMAL,
    BOOLEAN,
    JSON
}
