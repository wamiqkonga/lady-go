package com.ladygo.support.domain;

/** Persisted by name; values must match the CHECK constraint in the Flyway migrations (see EnumSchemaParityTest). */
public enum IncidentType {
    SOS,
    REPORT_DRIVER,
    REPORT_CUSTOMER,
    OTHER
}
