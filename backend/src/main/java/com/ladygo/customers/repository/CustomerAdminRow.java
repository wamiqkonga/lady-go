package com.ladygo.customers.repository;

import java.time.Instant;
import java.util.UUID;
import com.ladygo.users.domain.UserStatus;

/** One customer joined to their user row, as read by the admin customers list. */
public record CustomerAdminRow(
        UUID id, UUID userId, String name, String phone, UserStatus status, Instant createdAt, Instant lastLoginAt) {
}
