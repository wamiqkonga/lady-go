package com.ladygo.bookings.service;

import java.util.UUID;
import com.ladygo.bookings.domain.BookingStatus;

/**
 * Published after every successful {@link BookingStateMachine} transition. No subscriber exists in
 * Phase 4 (docs/api/booking.md section 4) - {@code MatchingService} in Phase 6 is the first real one.
 */
public record BookingStatusChangedEvent(UUID bookingId, BookingStatus from, BookingStatus to) {
}
