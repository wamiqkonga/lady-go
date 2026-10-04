package com.ladygo.notifications.domain;

/** Persisted by name; values must match the CHECK constraint in the Flyway migrations (see EnumSchemaParityTest). */
public enum NotificationStatus {
    QUEUED,
    SENT,
    DELIVERED,
    FAILED
}
