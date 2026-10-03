package com.ladygo.support.domain;

/** Persisted by name; values must match the CHECK constraint in the Flyway migrations (see EnumSchemaParityTest). */
public enum IncidentStatus {
    OPEN,
    ACKNOWLEDGED,
    ESCALATED,
    RESOLVED
}
