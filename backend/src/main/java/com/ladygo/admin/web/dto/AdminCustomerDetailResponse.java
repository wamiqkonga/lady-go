package com.ladygo.admin.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.ladygo.users.domain.UserStatus;

/** Customer profile, account status, counts and the most recent rides (newest first, capped). */
public record AdminCustomerDetailResponse(
        UUID id, String name, String phoneMasked, UserStatus status, Instant statusChangedAt, Instant createdAt,
        Instant lastLoginAt, long totalRides, long completedRides, long cancelledRides,
        List<AdminCustomerRideResponse> rides) {
}
