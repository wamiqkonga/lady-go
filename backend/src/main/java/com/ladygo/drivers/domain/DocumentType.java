package com.ladygo.drivers.domain;

/** Persisted by name; values must match the CHECK constraint in the Flyway migrations (see EnumSchemaParityTest). */
public enum DocumentType {
    GOVERNMENT_ID,
    LICENSE,
    VEHICLE_REGISTRATION,
    VEHICLE_PHOTO,
    INSURANCE,
    FITNESS_CERTIFICATE,
    POLLUTION_CERTIFICATE,
    POLICE_VERIFICATION,
    PERMIT,
    PHOTO,
    SELFIE,
    OTHER
}
