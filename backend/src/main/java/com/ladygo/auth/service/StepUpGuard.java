package com.ladygo.auth.service;

import java.time.Duration;
import java.time.Instant;
import com.ladygo.common.exception.MfaEnrolmentRequiredException;
import com.ladygo.common.exception.StepUpRequiredException;
import com.ladygo.common.exception.UnauthorizedException;
import com.ladygo.config.ConfigurationService;
import com.ladygo.security.jwt.AuthPrincipal;
import com.ladygo.users.domain.User;
import com.ladygo.users.repository.UserRepository;
import org.springframework.stereotype.Component;

/**
 * Server-side enforcement of step-up MFA (docs/security/admin-stepup-lockout-recovery-design.md section 2).
 * Controllers of destructive admin actions call {@link #require} first; the UI prompt is only a convenience.
 * Destructive actions are never allowed for an account with no authenticator, even when login MFA is
 * switched off for the pilot (LADYGO_ADMIN_MFA_REQUIRED=false).
 */
@Component
public class StepUpGuard {

    static final String WINDOW_KEY = "admin.stepup.window.minutes";

    private final UserRepository users;
    private final ConfigurationService config;

    public StepUpGuard(UserRepository users, ConfigurationService config) {
        this.users = users;
        this.config = config;
    }

    public void require(AuthPrincipal principal) {
        if (principal == null || principal.enrolment()) {
            throw new UnauthorizedException();
        }
        User user = users.findById(principal.userId()).orElseThrow(UnauthorizedException::new);
        if (!user.isMfaEnabled()) {
            throw new MfaEnrolmentRequiredException();
        }
        Duration window = config.getMinutes(WINDOW_KEY, Duration.ofMinutes(5));
        Instant stepUpAt = principal.stepUpAt();
        if (stepUpAt == null || stepUpAt.isBefore(Instant.now().minus(window))) {
            throw new StepUpRequiredException();
        }
    }
}
