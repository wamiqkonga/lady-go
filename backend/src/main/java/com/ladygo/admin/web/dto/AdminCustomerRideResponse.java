package com.ladygo.admin.web.dto;

import java.time.Instant;
import java.util.UUID;
import com.ladygo.bookings.domain.BookingStatus;

/** One entry of a customer's ride history in the admin portal; fares in minor currency units. */
public record AdminCustomerRideResponse(
        UUID bookingId, BookingStatus status, Instant createdAt, Long estimatedFare, Long finalFare,
        String currency) {
}
