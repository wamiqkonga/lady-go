package com.ladygo.admin.web.dto;

import java.time.Instant;
import java.util.UUID;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.rides.domain.RideStatus;

/** One row of the admin rides list (docs/product/admin-web-design.md section 4). */
public record AdminRideSummaryResponse(
        UUID bookingId,
        UUID rideId,
        UUID customerId,
        UUID driverId,
        BookingStatus bookingStatus,
        RideStatus rideStatus,
        Instant createdAt,
        Long estimatedFare,
        Long finalFare,
        long openFlagCount) {
}
