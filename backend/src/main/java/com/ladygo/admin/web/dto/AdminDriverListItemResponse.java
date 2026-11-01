package com.ladygo.admin.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import com.ladygo.drivers.domain.DriverAvailability;
import com.ladygo.drivers.domain.DriverVerificationStatus;

/** One row of the admin Drivers list. The phone is always masked, as in the driver review screen. */
public record AdminDriverListItemResponse(
        UUID id,
        String name,
        String phoneMasked,
        DriverVerificationStatus verificationStatus,
        DriverAvailability availabilityStatus,
        BigDecimal ratingAvg,
        int totalRides,
        Instant createdAt) {
}
