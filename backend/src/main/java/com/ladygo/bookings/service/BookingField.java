package com.ladygo.bookings.service;

/** Every booking-related piece of information that is visible to some viewers in some states and not
 *  others - the thing {@link BookingVisibilityPolicy} decides, field by field, never by inference. */
public enum BookingField {
    /** The real street address/coordinates of pickup. */
    PICKUP_ADDRESS_EXACT,
    /** The real street address/coordinates of the destination. */
    DESTINATION_ADDRESS_EXACT,
    /** The coarse, grid-rounded pickup area shown to a driver who has only been offered the booking
     *  (docs/api/matching.md section 3) - mutually exclusive in practice with PICKUP_ADDRESS_EXACT for
     *  the same viewer, but modelled as its own field since they're genuinely different data. */
    PICKUP_LOCATION_APPROXIMATE,
    /** The 4-digit ride-start PIN (docs/api/booking.md section 3, decision B4) - the customer's own
     *  secret to read out at pickup; the driver proves identity by being TOLD it, never by reading it
     *  from the API. */
    RIDE_PIN,
    /** Name/photo/rating/vehicle of the matched driver, shown to the customer. */
    DRIVER_SUMMARY,
    /** The rider's per-booking selfie (docs/api/driver-onboarding.md section 4) - a signed URL to a
     *  watermarked derivative, delivered through {@link RiderSelfieDeliveryService}, not a field on
     *  {@link com.ladygo.bookings.web.dto.BookingDetailResponse}; modelled here so the SAME policy
     *  governs it instead of a second, separately-maintained set of rules. */
    RIDER_SELFIE,
    /** When the driver confirmed receiving cash for this booking - driver-only (a rider has no
     *  equivalent field), so a driver app killed mid-ride can restore its cash step. */
    CASH_CONFIRMED_AT
}
