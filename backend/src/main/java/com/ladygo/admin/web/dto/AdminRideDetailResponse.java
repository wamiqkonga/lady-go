package com.ladygo.admin.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.common.domain.ActorType;
import com.ladygo.payments.domain.PaymentMode;
import com.ladygo.rides.domain.RideStatus;

/** Read-only - no admin write action on a ride itself in the pilot (docs/product/admin-web-design.md
 *  section 8, open question 2 - decided: read-only for v1). {@code pickupAddress} is null unless
 *  {@link com.ladygo.bookings.service.BookingVisibilityPolicy} currently grants ADMIN that field for
 *  this booking's status - never dumped unconditionally, so a future tightening of that policy is
 *  automatically honoured here too, not just by the customer/driver-facing endpoints. Deliberately no
 *  rider live location and no trip-share token anywhere on this response. */
public record AdminRideDetailResponse(
        UUID bookingId,
        UUID customerId,
        UUID driverId,
        AdminPersonRefResponse customer,
        AdminPersonRefResponse driver,
        BookingStatus bookingStatus,
        PaymentMode paymentMethod,
        String pickupAddress,
        String cancelReason,
        ActorType cancelledBy,
        Instant cashConfirmedAt,
        Instant cashDisputedAt,
        Instant createdAt,
        Long estimatedFare,
        Long finalFare,
        UUID rideId,
        RideStatus rideStatus,
        Instant startedAt,
        Instant completedAt,
        List<AdminRideFlagResponse> flags,
        List<AdminBookingEventResponse> timeline) {
}
