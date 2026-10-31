package com.ladygo.auth.service;

import java.time.Duration;
import java.util.UUID;
import com.ladygo.config.ConfigurationService;
import com.ladygo.security.jwt.JwtService;
import com.ladygo.users.domain.UserRole;
import org.springframework.stereotype.Component;

/**
 * Picks the right access-token TTL per role (docs/security/auth-design.md section 5: 15 min for
 * CUSTOMER/DRIVER, 10 min "short sessions" for staff) so every caller that issues a token - OTP login,
 * admin login, refresh - applies the same rule.
 */
@Component
public class AccessTokenIssuer {

    private final JwtService jwtService;
    private final ConfigurationService config;

    public AccessTokenIssuer(JwtService jwtService, ConfigurationService config) {
        this.jwtService = jwtService;
        this.config = config;
    }

    public String issue(UUID userId, UserRole role) {
        return issue(userId, role, null);
    }

    public String issue(UUID userId, UserRole role, java.time.Instant stepUpAt) {
        Duration ttl = isStaff(role)
                ? config.getMinutes("admin.access.token.ttl.minutes", Duration.ofMinutes(10))
                : config.getMinutes("access.token.ttl.minutes", Duration.ofMinutes(15));
        return jwtService.issueAccessToken(userId, role, ttl, stepUpAt);
    }

    private boolean isStaff(UserRole role) {
        return role == UserRole.ADMIN || role == UserRole.SUPER_ADMIN || role == UserRole.SUPPORT;
    }
}
