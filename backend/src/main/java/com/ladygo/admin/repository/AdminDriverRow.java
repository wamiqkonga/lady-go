package com.ladygo.admin.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import com.ladygo.drivers.domain.DriverAvailability;
import com.ladygo.drivers.domain.DriverVerificationStatus;

/** Query projection of a driver joined to their user; the service masks the phone before it leaves. */
public record AdminDriverRow(UUID id, String name, String phone, DriverVerificationStatus verificationStatus,
        DriverAvailability availabilityStatus, BigDecimal ratingAvg, int totalRides, Instant createdAt) {
}
