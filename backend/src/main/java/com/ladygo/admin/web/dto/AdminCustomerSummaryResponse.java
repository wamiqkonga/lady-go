package com.ladygo.admin.web.dto;

import java.time.Instant;
import java.util.UUID;
import com.ladygo.users.domain.UserStatus;

/** One row of the admin customers list. The phone is always masked (last 4 digits only). */
public record AdminCustomerSummaryResponse(
        UUID id, String name, String phoneMasked, UserStatus status, Instant createdAt, Instant lastLoginAt) {
}
