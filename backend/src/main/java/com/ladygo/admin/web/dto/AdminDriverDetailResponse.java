package com.ladygo.admin.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.drivers.domain.DriverAvailability;
import com.ladygo.drivers.domain.DriverVerificationStatus;

/** Everything the Drivers detail page shows. Money is in minor units; the phone is masked. */
public record AdminDriverDetailResponse(
        UUID id,
        String name,
        String phoneMasked,
        UUID cityId,
        DriverVerificationStatus verificationStatus,
        DriverAvailability availabilityStatus,
        BigDecimal ratingAvg,
        int ratingCount,
        int totalRides,
        Instant verifiedAt,
        String rejectionReason,
        Instant createdAt,
        VehicleAdminResponse vehicle,
        DocumentsSummary documents,
        List<RecentRide> recentRides,
        EarningsTotals earnings) {

    /** {@code reviewPath} is the admin-app route of the existing driver review screen. */
    public record DocumentsSummary(int total, int pending, int approved, int rejected, String reviewPath) {
    }

    public record RecentRide(UUID bookingId, BookingStatus status, Instant createdAt, Long finalFare, String currency) {
    }

    public record EarningsTotals(long rides, long gross, long commission, long net) {
    }
}
