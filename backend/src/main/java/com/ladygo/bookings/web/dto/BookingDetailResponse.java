package com.ladygo.bookings.web.dto;

import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.payments.domain.PaymentMode;
import com.ladygo.vehicles.domain.VehicleType;
import java.time.Instant;
import java.util.UUID;

public record BookingDetailResponse(
        UUID id,
        BookingStatus status,
        String pickupAddress,
        String destinationAddress,
        VehicleType vehicleType,
        PaymentMode paymentMethod,
        Long estimatedFare,
        Long finalFare,
        String currency,
        String ridePin,
        DriverSummaryResponse driver,
        Instant createdAt,
        Instant scheduledAt,
        /** Driver-facing only (null for riders and in history lists) - see BookingField.CASH_CONFIRMED_AT. */
        Instant cashConfirmedAt,
        /** The pickup and destination points, for the maps. Each pair is present exactly when the matching exact
         *  address is (the same {@code BookingVisibilityPolicy} fields), and null in history lists. */
        Double pickupLatitude,
        Double pickupLongitude,
        Double destinationLatitude,
        Double destinationLongitude) {
}
