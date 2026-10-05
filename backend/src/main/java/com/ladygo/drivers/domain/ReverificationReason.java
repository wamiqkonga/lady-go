package com.ladygo.drivers.domain;

/** Persisted by name; values must match the CHECK constraint in the Flyway migrations (see EnumSchemaParityTest).
 *  Deliberately excludes INITIAL - a re-verification is, by definition, never the first check. */
public enum ReverificationReason {
    RANDOM_REVERIFICATION,
    REPORTED
}
