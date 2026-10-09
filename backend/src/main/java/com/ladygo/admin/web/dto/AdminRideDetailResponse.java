package com.ladygo.admin.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.common.domain.ActorType;
import com.ladygo.payments.domain.PaymentMode;
import com.ladygo.rides.domain.RideStatus;

/** Read-only - no admin write action on a ride itself in the pilot (docs/product/admin-web-design.md
 *  section 8, open question 2 - decided: read-only for v1). */
public record AdminRideDetailResponse(
        UUID bookingId,
        UUID customerId,
        UUID driverId,
        BookingStatus bookingStatus,
        PaymentMode paymentMethod,
        String cancelReason,
        ActorType cancelledBy,
        Instant createdAt,
        Long estimatedFare,
        Long finalFare,
        UUID rideId,
        RideStatus rideStatus,
        Instant startedAt,
        Instant completedAt,
        List<AdminRideFlagResponse> flags) {
}
