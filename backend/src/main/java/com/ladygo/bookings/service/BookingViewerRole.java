package com.ladygo.bookings.service;

/**
 * Who is asking to see a booking-related field - the other axis of
 * {@link BookingVisibilityPolicy}'s allow-list, alongside {@link com.ladygo.bookings.domain.BookingStatus}.
 * Deliberately excludes "unrelated party" - ownership (is this even the customer's/driver's booking
 * at all) is checked before the policy is ever consulted, same as today; this enum only governs WHICH
 * fields a genuinely-related viewer sees, not WHETHER they may look at all.
 */
public enum BookingViewerRole {
    CUSTOMER,
    DRIVER,
    /** Used by AdminRideOversightService's ride-detail endpoint (docs/product/admin-web-design.md
     *  section 4 update) to decide whether to include the exact pickup address, rather than that
     *  endpoint inlining its own unconditional "admins always see everything" rule. */
    ADMIN
}
