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
    /** No admin booking-review endpoint exists yet (docs/api/driver-onboarding.md section 3 - admin
     *  scope today is driver-documents-and-eligibility-reports only). Defined now, ahead of use, so a
     *  future admin support/review tool has an explicit rule to call rather than reusing CUSTOMER's or
     *  DRIVER's by accident. */
    ADMIN
}
