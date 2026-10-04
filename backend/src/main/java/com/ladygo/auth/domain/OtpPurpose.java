package com.ladygo.auth.domain;

/** Persisted by name; values must match the CHECK constraint in the Flyway migrations (see EnumSchemaParityTest). */
public enum OtpPurpose {
    LOGIN,
    PHONE_VERIFY,
    ADMIN_MFA
}
