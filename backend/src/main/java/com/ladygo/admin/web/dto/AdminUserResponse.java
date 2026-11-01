package com.ladygo.admin.web.dto;

import java.time.Instant;
import java.util.UUID;
import com.ladygo.users.domain.UserRole;
import com.ladygo.users.domain.UserStatus;

/** A staff account as the SUPER_ADMIN screen sees it. Deliberately no password hash, no MFA secret, no full email. */
public record AdminUserResponse(
        UUID id, String name, String maskedEmail, UserRole role, UserStatus status, boolean mfaEnrolled,
        Instant lastLoginAt, boolean locked, boolean pendingInvite, Instant createdAt) {
}
