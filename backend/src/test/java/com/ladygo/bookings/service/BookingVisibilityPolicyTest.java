package com.ladygo.bookings.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ladygo.bookings.domain.BookingStatus;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Exhaustive, explicit allow-list for every (viewer role) x (booking status) combination - every cell
 * is written out by hand rather than derived from a formula, so a change to the policy that widens or
 * narrows visibility for even one cell shows up as a specific, named test failure rather than a
 * passing-by-coincidence ordinal comparison (see {@link ArchitectureTest} for why ordinal comparisons
 * are banned outright - this table is the replacement discipline).
 */
class BookingVisibilityPolicyTest {

    private static final Set<BookingField> ALL_FIELDS = EnumSet.allOf(BookingField.class);

    private static Set<BookingField> fields(BookingField... fields) {
        return fields.length == 0 ? EnumSet.noneOf(BookingField.class) : EnumSet.of(fields[0], fields);
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                // --- CUSTOMER: always sees their own addresses; PIN before RIDE_STARTED; driver
                //     summary once a driver has been found (DRIVER_ASSIGNED+), never for CANCELLED. ---
                Arguments.of(BookingViewerRole.CUSTOMER, BookingStatus.REQUESTED,
                        fields(BookingField.PICKUP_ADDRESS_EXACT, BookingField.DESTINATION_ADDRESS_EXACT,
                                BookingField.RIDE_PIN)),
                Arguments.of(BookingViewerRole.CUSTOMER, BookingStatus.SEARCHING_DRIVER,
                        fields(BookingField.PICKUP_ADDRESS_EXACT, BookingField.DESTINATION_ADDRESS_EXACT,
                                BookingField.RIDE_PIN)),
                Arguments.of(BookingViewerRole.CUSTOMER, BookingStatus.DRIVER_ASSIGNED,
                        fields(BookingField.PICKUP_ADDRESS_EXACT, BookingField.DESTINATION_ADDRESS_EXACT,
                                BookingField.RIDE_PIN, BookingField.DRIVER_SUMMARY)),
                Arguments.of(BookingViewerRole.CUSTOMER, BookingStatus.DRIVER_ACCEPTED,
                        fields(BookingField.PICKUP_ADDRESS_EXACT, BookingField.DESTINATION_ADDRESS_EXACT,
                                BookingField.RIDE_PIN, BookingField.DRIVER_SUMMARY)),
                Arguments.of(BookingViewerRole.CUSTOMER, BookingStatus.DRIVER_ARRIVING,
                        fields(BookingField.PICKUP_ADDRESS_EXACT, BookingField.DESTINATION_ADDRESS_EXACT,
                                BookingField.RIDE_PIN, BookingField.DRIVER_SUMMARY)),
                Arguments.of(BookingViewerRole.CUSTOMER, BookingStatus.DRIVER_ARRIVED,
                        fields(BookingField.PICKUP_ADDRESS_EXACT, BookingField.DESTINATION_ADDRESS_EXACT,
                                BookingField.RIDE_PIN, BookingField.DRIVER_SUMMARY)),
                Arguments.of(BookingViewerRole.CUSTOMER, BookingStatus.RIDE_STARTED,
                        fields(BookingField.PICKUP_ADDRESS_EXACT, BookingField.DESTINATION_ADDRESS_EXACT,
                                BookingField.DRIVER_SUMMARY)),
                Arguments.of(BookingViewerRole.CUSTOMER, BookingStatus.RIDE_COMPLETED,
                        fields(BookingField.PICKUP_ADDRESS_EXACT, BookingField.DESTINATION_ADDRESS_EXACT,
                                BookingField.DRIVER_SUMMARY)),
                Arguments.of(BookingViewerRole.CUSTOMER, BookingStatus.PAYMENT_PENDING,
                        fields(BookingField.PICKUP_ADDRESS_EXACT, BookingField.DESTINATION_ADDRESS_EXACT,
                                BookingField.DRIVER_SUMMARY)),
                Arguments.of(BookingViewerRole.CUSTOMER, BookingStatus.COMPLETED,
                        fields(BookingField.PICKUP_ADDRESS_EXACT, BookingField.DESTINATION_ADDRESS_EXACT,
                                BookingField.DRIVER_SUMMARY)),
                Arguments.of(BookingViewerRole.CUSTOMER, BookingStatus.CANCELLED,
                        fields(BookingField.PICKUP_ADDRESS_EXACT, BookingField.DESTINATION_ADDRESS_EXACT)),

                // --- DRIVER: nothing before being offered; approximate-only while offered; exact
                //     address + rider selfie only once accepted; selfie hides again at RIDE_STARTED;
                //     NEVER the ride PIN or the (self-referential) driver summary, in any status;
                //     NOTHING at all once cancelled, however far it had gotten. ---
                Arguments.of(BookingViewerRole.DRIVER, BookingStatus.REQUESTED, fields()),
                Arguments.of(BookingViewerRole.DRIVER, BookingStatus.SEARCHING_DRIVER, fields()),
                Arguments.of(BookingViewerRole.DRIVER, BookingStatus.DRIVER_ASSIGNED,
                        fields(BookingField.PICKUP_LOCATION_APPROXIMATE)),
                Arguments.of(BookingViewerRole.DRIVER, BookingStatus.DRIVER_ACCEPTED,
                        fields(BookingField.PICKUP_ADDRESS_EXACT, BookingField.DESTINATION_ADDRESS_EXACT,
                                BookingField.RIDER_SELFIE)),
                Arguments.of(BookingViewerRole.DRIVER, BookingStatus.DRIVER_ARRIVING,
                        fields(BookingField.PICKUP_ADDRESS_EXACT, BookingField.DESTINATION_ADDRESS_EXACT,
                                BookingField.RIDER_SELFIE)),
                Arguments.of(BookingViewerRole.DRIVER, BookingStatus.DRIVER_ARRIVED,
                        fields(BookingField.PICKUP_ADDRESS_EXACT, BookingField.DESTINATION_ADDRESS_EXACT,
                                BookingField.RIDER_SELFIE)),
                Arguments.of(BookingViewerRole.DRIVER, BookingStatus.RIDE_STARTED,
                        fields(BookingField.PICKUP_ADDRESS_EXACT, BookingField.DESTINATION_ADDRESS_EXACT)),
                Arguments.of(BookingViewerRole.DRIVER, BookingStatus.RIDE_COMPLETED,
                        fields(BookingField.PICKUP_ADDRESS_EXACT, BookingField.DESTINATION_ADDRESS_EXACT)),
                Arguments.of(BookingViewerRole.DRIVER, BookingStatus.PAYMENT_PENDING,
                        fields(BookingField.PICKUP_ADDRESS_EXACT, BookingField.DESTINATION_ADDRESS_EXACT)),
                Arguments.of(BookingViewerRole.DRIVER, BookingStatus.COMPLETED,
                        fields(BookingField.PICKUP_ADDRESS_EXACT, BookingField.DESTINATION_ADDRESS_EXACT)),
                Arguments.of(BookingViewerRole.DRIVER, BookingStatus.CANCELLED, fields()),

                // --- ADMIN: everything, every status - no admin booking-review caller exists yet
                //     (see BookingViewerRole), but the rule is defined explicitly rather than left to
                //     default/inference. ---
                Arguments.of(BookingViewerRole.ADMIN, BookingStatus.REQUESTED, ALL_FIELDS),
                Arguments.of(BookingViewerRole.ADMIN, BookingStatus.SEARCHING_DRIVER, ALL_FIELDS),
                Arguments.of(BookingViewerRole.ADMIN, BookingStatus.DRIVER_ASSIGNED, ALL_FIELDS),
                Arguments.of(BookingViewerRole.ADMIN, BookingStatus.DRIVER_ACCEPTED, ALL_FIELDS),
                Arguments.of(BookingViewerRole.ADMIN, BookingStatus.DRIVER_ARRIVING, ALL_FIELDS),
                Arguments.of(BookingViewerRole.ADMIN, BookingStatus.DRIVER_ARRIVED, ALL_FIELDS),
                Arguments.of(BookingViewerRole.ADMIN, BookingStatus.RIDE_STARTED, ALL_FIELDS),
                Arguments.of(BookingViewerRole.ADMIN, BookingStatus.RIDE_COMPLETED, ALL_FIELDS),
                Arguments.of(BookingViewerRole.ADMIN, BookingStatus.PAYMENT_PENDING, ALL_FIELDS),
                Arguments.of(BookingViewerRole.ADMIN, BookingStatus.COMPLETED, ALL_FIELDS),
                Arguments.of(BookingViewerRole.ADMIN, BookingStatus.CANCELLED, ALL_FIELDS));
    }

    @ParameterizedTest(name = "{0} at {1} sees exactly {2}")
    @MethodSource("cases")
    void visibleFieldsMatchesTheAllowListExactly(
            BookingViewerRole role, BookingStatus status, Set<BookingField> expected) {
        assertThat(BookingVisibilityPolicy.visibleFields(role, status)).isEqualTo(expected);
    }

    /** Every (role, status) combination must appear exactly once above - catches a copy-paste that
     *  duplicated one row and silently dropped another, which an exact-match assertion alone wouldn't. */
    @org.junit.jupiter.api.Test
    void everyRoleAndStatusCombinationIsCoveredExactlyOnce() {
        long expectedCombinations = (long) BookingViewerRole.values().length * BookingStatus.values().length;
        assertThat(cases().count()).isEqualTo(expectedCombinations);
        Set<String> seen = new java.util.HashSet<>();
        cases().forEach(args -> {
            Object[] a = args.get();
            String key = a[0] + ":" + a[1];
            assertThat(seen.add(key)).as("duplicate row for " + key).isTrue();
        });
    }
}
