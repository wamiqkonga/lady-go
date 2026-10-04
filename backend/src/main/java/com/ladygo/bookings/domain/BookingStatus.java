package com.ladygo.bookings.domain;

/** Persisted by name; values must match the CHECK constraint in the Flyway migrations (see EnumSchemaParityTest). */
public enum BookingStatus {
    REQUESTED,
    SEARCHING_DRIVER,
    DRIVER_ASSIGNED,
    DRIVER_ACCEPTED,
    DRIVER_ARRIVING,
    DRIVER_ARRIVED,
    RIDE_STARTED,
    RIDE_COMPLETED,
    PAYMENT_PENDING,
    COMPLETED,
    CANCELLED
}
