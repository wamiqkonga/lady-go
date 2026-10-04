package com.ladygo.security.jwt;

import java.util.UUID;
import com.ladygo.users.domain.UserRole;

/**
 * Resolved identity of an authenticated request. A normal token carries {@code role}; an enrolment
 * token (admin forced password change / MFA setup, see docs/security/auth-design.md section 3) carries
 * no role and is only ever accepted by the enrolment endpoints, which check {@link #enrolment()} explicitly.
 */
public record AuthPrincipal(UUID userId, UserRole role, boolean enrolment, boolean passwordChanged, boolean mfaEnrolled) {

    public static AuthPrincipal authenticated(UUID userId, UserRole role) {
        return new AuthPrincipal(userId, role, false, true, true);
    }

    public static AuthPrincipal enrolment(UUID userId, boolean passwordChanged, boolean mfaEnrolled) {
        return new AuthPrincipal(userId, null, true, passwordChanged, mfaEnrolled);
    }
}
