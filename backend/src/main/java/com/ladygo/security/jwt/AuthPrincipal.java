package com.ladygo.security.jwt;

import java.time.Instant;
import java.util.UUID;
import com.ladygo.users.domain.UserRole;

/**
 * Resolved identity of an authenticated request. A normal token carries {@code role}; an enrolment
 * token (admin forced password change / MFA setup, see docs/security/auth-design.md section 3) carries
 * no role and is only ever accepted by the enrolment endpoints, which check {@link #enrolment()} explicitly.
 *
 * <p>{@code stepUpAt} is when this session last re-proved a TOTP code (docs/security/admin-stepup-lockout-
 * recovery-design.md section 2); null for every token that was not issued by a step-up or MFA enrolment.
 */
public record AuthPrincipal(UUID userId, UserRole role, boolean enrolment, boolean passwordChanged,
                            boolean mfaEnrolled, Instant stepUpAt) {

    public static AuthPrincipal authenticated(UUID userId, UserRole role) {
        return new AuthPrincipal(userId, role, false, true, true, null);
    }

    public static AuthPrincipal authenticated(UUID userId, UserRole role, Instant stepUpAt) {
        return new AuthPrincipal(userId, role, false, true, true, stepUpAt);
    }

    public static AuthPrincipal enrolment(UUID userId, boolean passwordChanged, boolean mfaEnrolled) {
        return new AuthPrincipal(userId, null, true, passwordChanged, mfaEnrolled, null);
    }
}
