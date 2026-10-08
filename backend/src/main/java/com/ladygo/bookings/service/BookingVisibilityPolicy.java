package com.ladygo.bookings.service;

import java.util.EnumSet;
import java.util.Set;
import com.ladygo.bookings.domain.BookingStatus;

/**
 * The single place that decides which {@link BookingField}s a given {@link BookingViewerRole} may see
 * for a booking in a given {@link BookingStatus} - every booking/offer/selfie read path in this
 * codebase calls this instead of each inventing (and inevitably drifting from) its own rule.
 *
 * <p>Deliberately table-driven by explicit status membership, never by
 * {@code status.ordinal()} comparison - an ordinal-based gate on this exact enum was tried once
 * already and was itself the bug (adversarial review finding, CRITICAL: {@code CANCELLED} is the LAST
 * value in {@link BookingStatus}, so an ordinal inequality meant to require "accepted or later"
 * actually ADMITTED a cancelled booking too). {@code ArchitectureTest} fails the build on any
 * {@code .ordinal()} call on a status/security-relevant enum, specifically so this class's own
 * mistake can't be repeated by anything, here or elsewhere.
 */
public final class BookingVisibilityPolicy {

    /** docs/api/booking.md section 3 (decision B4): visible to the customer before the ride starts -
     *  written out explicitly rather than "anything before RIDE_STARTED" so a future status inserted
     *  anywhere in the enum can't silently change this set by changing declaration order. */
    private static final Set<BookingStatus> RIDE_PIN_VISIBLE_TO_CUSTOMER = EnumSet.of(
            BookingStatus.REQUESTED, BookingStatus.SEARCHING_DRIVER, BookingStatus.DRIVER_ASSIGNED,
            BookingStatus.DRIVER_ACCEPTED, BookingStatus.DRIVER_ARRIVING, BookingStatus.DRIVER_ARRIVED);

    /** docs/api/booking.md section 6: null until a driver has been found. */
    private static final Set<BookingStatus> DRIVER_SUMMARY_VISIBLE_TO_CUSTOMER = EnumSet.of(
            BookingStatus.DRIVER_ASSIGNED, BookingStatus.DRIVER_ACCEPTED, BookingStatus.DRIVER_ARRIVING,
            BookingStatus.DRIVER_ARRIVED, BookingStatus.RIDE_STARTED, BookingStatus.RIDE_COMPLETED,
            BookingStatus.PAYMENT_PENDING, BookingStatus.COMPLETED);

    /** docs/api/matching.md section 3: only while this specific driver has a live, not-yet-accepted
     *  offer - in practice the only status a driver can be looking at an unaccepted offer from. */
    private static final Set<BookingStatus> APPROXIMATE_PICKUP_VISIBLE_TO_OFFERED_DRIVER = EnumSet.of(
            BookingStatus.DRIVER_ASSIGNED);

    /** docs/api/matching.md section 6: the exact address becomes visible to the accepting driver only,
     *  from acceptance onward. */
    private static final Set<BookingStatus> EXACT_ADDRESS_VISIBLE_TO_DRIVER = EnumSet.of(
            BookingStatus.DRIVER_ACCEPTED, BookingStatus.DRIVER_ARRIVING, BookingStatus.DRIVER_ARRIVED,
            BookingStatus.RIDE_STARTED, BookingStatus.RIDE_COMPLETED, BookingStatus.PAYMENT_PENDING,
            BookingStatus.COMPLETED);

    /** docs/api/driver-onboarding.md section 4: visible to the accepting driver only, hidden again once
     *  the ride starts - never before acceptance, never after the ride is under way. */
    private static final Set<BookingStatus> RIDER_SELFIE_VISIBLE_TO_DRIVER = EnumSet.of(
            BookingStatus.DRIVER_ACCEPTED, BookingStatus.DRIVER_ARRIVING, BookingStatus.DRIVER_ARRIVED);

    private BookingVisibilityPolicy() {
    }

    /** Deliberately {@code if}/{@code ==} rather than a {@code switch} on {@code role}: javac compiles
     *  a switch over an enum to a jump table indexed by {@code ordinal()} (confirmed by writing a
     *  throwaway violation and watching {@code ArchitectureTest} catch THIS method's own original
     *  switch form) - exactly the kind of hidden ordinal() dependency that rule exists to ban, even
     *  though a plain dispatch-by-switch is not the comparison bug that started this. Enum {@code ==}
     *  is reference equality; it never calls {@code ordinal()} at all. */
    public static Set<BookingField> visibleFields(BookingViewerRole role, BookingStatus status) {
        if (role == BookingViewerRole.CUSTOMER) {
            return customerFields(status);
        }
        if (role == BookingViewerRole.DRIVER) {
            return driverFields(status);
        }
        return EnumSet.allOf(BookingField.class); // ADMIN - no caller exists yet, see BookingViewerRole.
    }

    public static boolean isVisible(BookingViewerRole role, BookingStatus status, BookingField field) {
        return visibleFields(role, status).contains(field);
    }

    private static Set<BookingField> customerFields(BookingStatus status) {
        EnumSet<BookingField> fields = EnumSet.of(
                BookingField.PICKUP_ADDRESS_EXACT, BookingField.DESTINATION_ADDRESS_EXACT);
        if (RIDE_PIN_VISIBLE_TO_CUSTOMER.contains(status)) {
            fields.add(BookingField.RIDE_PIN);
        }
        if (DRIVER_SUMMARY_VISIBLE_TO_CUSTOMER.contains(status)) {
            fields.add(BookingField.DRIVER_SUMMARY);
        }
        return fields;
    }

    private static Set<BookingField> driverFields(BookingStatus status) {
        EnumSet<BookingField> fields = EnumSet.noneOf(BookingField.class);
        if (APPROXIMATE_PICKUP_VISIBLE_TO_OFFERED_DRIVER.contains(status)) {
            fields.add(BookingField.PICKUP_LOCATION_APPROXIMATE);
        }
        if (EXACT_ADDRESS_VISIBLE_TO_DRIVER.contains(status)) {
            fields.add(BookingField.PICKUP_ADDRESS_EXACT);
            fields.add(BookingField.DESTINATION_ADDRESS_EXACT);
        }
        if (RIDER_SELFIE_VISIBLE_TO_DRIVER.contains(status)) {
            fields.add(BookingField.RIDER_SELFIE);
        }
        // RIDE_PIN and DRIVER_SUMMARY are never in a driver's set, in any status - see their javadoc.
        return fields;
    }
}
