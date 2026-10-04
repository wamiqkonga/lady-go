package com.ladygo.auth.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import com.ladygo.admin.domain.AuditLog;
import com.ladygo.admin.repository.AuditLogRepository;
import com.ladygo.common.exception.ForbiddenException;
import com.ladygo.common.exception.UnauthorizedException;
import com.ladygo.config.ConfigurationService;
import com.ladygo.security.crypto.MfaSecretCipher;
import com.ladygo.security.crypto.TotpService;
import com.ladygo.security.jwt.AuthPrincipal;
import com.ladygo.security.jwt.JwtService;
import com.ladygo.security.ratelimit.RateLimitExceededException;
import com.ladygo.security.ratelimit.RateLimiter;
import com.ladygo.users.domain.User;
import com.ladygo.users.domain.UserRole;
import com.ladygo.users.domain.UserStatus;
import com.ladygo.users.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staff (ADMIN/SUPER_ADMIN/SUPPORT) login, forced enrolment and lost-device recovery
 * (docs/security/auth-design.md section 3). CUSTOMER/DRIVER OTP login is {@link OtpLoginService}.
 */
@Service
public class AdminAuthService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthService.class);
    private static final String STEP_PASSWORD_CHANGE = "PASSWORD_CHANGE";
    private static final String STEP_MFA_ENROLL = "MFA_ENROLL";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final MfaSecretCipher mfaSecretCipher;
    private final TotpService totpService;
    private final JwtService jwtService;
    private final AccessTokenIssuer accessTokenIssuer;
    private final RefreshTokenService refreshTokenService;
    private final RateLimiter rateLimiter;
    private final ConfigurationService config;
    private final AuditLogRepository auditLogs;

    public AdminAuthService(
            UserRepository users,
            PasswordEncoder passwordEncoder,
            MfaSecretCipher mfaSecretCipher,
            TotpService totpService,
            JwtService jwtService,
            AccessTokenIssuer accessTokenIssuer,
            RefreshTokenService refreshTokenService,
            RateLimiter rateLimiter,
            ConfigurationService config,
            AuditLogRepository auditLogs) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.mfaSecretCipher = mfaSecretCipher;
        this.totpService = totpService;
        this.jwtService = jwtService;
        this.accessTokenIssuer = accessTokenIssuer;
        this.refreshTokenService = refreshTokenService;
        this.rateLimiter = rateLimiter;
        this.config = config;
        this.auditLogs = auditLogs;
    }

    @Transactional
    public AdminLoginOutcome login(String email, String password, String totpCode, String deviceId, String clientIp) {
        checkLoginRateLimit(email, clientIp);

        User user = users.findByEmailIgnoreCase(email).orElse(null);
        if (user == null || user.getPasswordHash() == null
                || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new UnauthorizedException();
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            // Adversarial review finding (CRITICAL): status was never checked on this path.
            throw new ForbiddenException("This account is not active.");
        }

        if (user.isMustChangePassword() || !user.isMfaEnabled()) {
            List<String> steps = new ArrayList<>();
            if (user.isMustChangePassword()) {
                steps.add(STEP_PASSWORD_CHANGE);
            }
            if (!user.isMfaEnabled()) {
                steps.add(STEP_MFA_ENROLL);
            }
            String token = jwtService.issueEnrolmentToken(
                    user.getId(), !user.isMustChangePassword(), user.isMfaEnabled(), enrolmentTtl());
            releaseLoginRateLimit(email, clientIp);
            return new AdminLoginOutcome.EnrolmentRequired(token, List.copyOf(steps));
        }

        long acceptedStep = verifyTotpOrThrow(user, totpCode);

        // Order matters: save() on a User object detached by the clearAutomatically bulk update below
        // would merge its stale in-memory mfaLastUsedStep back over the DB row, undoing replay
        // rejection for next time. So every other mutation is saved first; the atomic replay-acceptance
        // update runs last and is the only write that touches this user afterwards.
        user.setLastLoginAt(Instant.now());
        users.save(user);
        if (users.updateMfaLastUsedStepIfNewer(user.getId(), acceptedStep) == 0) {
            log.warn("operation=totp_replay_rejected userId={}", user.getId());
            throw new UnauthorizedException();
        }
        // Adversarial review finding (MEDIUM): a run of legitimate successful logins must not erode
        // the budget meant to catch repeated failures, or enough real logins self-lock the account.
        releaseLoginRateLimit(email, clientIp);
        String access = accessTokenIssuer.issue(user.getId(), user.getRole());
        String refresh = refreshTokenService.issue(user.getId(), deviceId);
        return new AdminLoginOutcome.Authenticated(new TokenPair(access, refresh));
    }

    @Transactional
    public void changePassword(AuthPrincipal principal, String currentPassword, String newPassword) {
        requireEnrolmentToken(principal);
        User user = users.findById(principal.userId()).orElseThrow(UnauthorizedException::new);
        if (user.getPasswordHash() == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new UnauthorizedException();
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        users.save(user);
    }

    public record MfaEnrollStart(String secret, String provisioningUri) {
    }

    @Transactional
    public MfaEnrollStart startMfaEnrollment(AuthPrincipal principal) {
        requireEnrolmentToken(principal);
        requirePasswordAlreadyChanged(principal);
        User user = users.findById(principal.userId()).orElseThrow(UnauthorizedException::new);
        String secret = totpService.generateSecret();
        user.setMfaPendingSecretEnc(mfaSecretCipher.encrypt(secret));
        users.save(user);
        return new MfaEnrollStart(secret, totpService.provisioningUri(user.getEmail(), secret));
    }

    @Transactional
    public void confirmMfaEnrollment(AuthPrincipal principal, String code) {
        requireEnrolmentToken(principal);
        requirePasswordAlreadyChanged(principal);
        User user = users.findById(principal.userId()).orElseThrow(UnauthorizedException::new);
        if (user.getMfaPendingSecretEnc() == null) {
            throw new UnauthorizedException();
        }
        String pendingSecret = mfaSecretCipher.decrypt(user.getMfaPendingSecretEnc());
        OptionalLong step = totpService.verify(pendingSecret, code, Instant.now());
        if (step.isEmpty()) {
            throw new UnauthorizedException();
        }

        // Same ordering rule as login(): the atomic replay-acceptance update must be the last write
        // touching this user, or a later save() would merge a stale mfaLastUsedStep back over it.
        user.setMfaSecretEnc(user.getMfaPendingSecretEnc());
        user.setMfaPendingSecretEnc(null);
        user.setMfaEnabled(true);
        users.save(user);
        if (users.updateMfaLastUsedStepIfNewer(user.getId(), step.getAsLong()) == 0) {
            throw new UnauthorizedException();
        }
    }

    /** SUPER_ADMIN-only lost-device recovery for another staff member (section 3.3). Never self-service. */
    @Transactional
    public void resetMfa(UUID actorId, UserRole actorRole, UUID targetUserId, String ip) {
        User target = users.findById(targetUserId).orElseThrow(UnauthorizedException::new);
        target.setMfaEnabled(false);
        target.setMfaSecretEnc(null);
        target.setMfaPendingSecretEnc(null);
        target.setMfaLastUsedStep(null);
        users.save(target);

        AuditLog entry = new AuditLog();
        entry.setActorId(actorId);
        entry.setActorRole(actorRole.name());
        entry.setAction("ADMIN_MFA_RESET");
        entry.setTargetType("users");
        entry.setTargetId(targetUserId.toString());
        entry.setIp(ip);
        auditLogs.save(entry);
    }

    /** Checks the TOTP code's validity only; does not write the replay-acceptance step - see login(). */
    private long verifyTotpOrThrow(User user, String totpCode) {
        if (totpCode == null || totpCode.isBlank() || user.getMfaSecretEnc() == null) {
            throw new UnauthorizedException();
        }
        String secret = mfaSecretCipher.decrypt(user.getMfaSecretEnc());
        OptionalLong step = totpService.verify(secret, totpCode, Instant.now());
        if (step.isEmpty()) {
            throw new UnauthorizedException();
        }
        return step.getAsLong();
    }

    private void requireEnrolmentToken(AuthPrincipal principal) {
        if (principal == null || !principal.enrolment()) {
            throw new UnauthorizedException();
        }
    }

    private void requirePasswordAlreadyChanged(AuthPrincipal principal) {
        if (!principal.passwordChanged()) {
            throw new ForbiddenException("Change your password before enrolling MFA.");
        }
    }

    private void checkLoginRateLimit(String email, String clientIp) {
        int maxAttempts = config.getInt("admin.login.max.attempts", 5);
        Duration window = config.getMinutes("admin.login.window.minutes", Duration.ofMinutes(15));
        boolean emailOk = rateLimiter.tryConsume(emailBucket(email), maxAttempts, window);
        boolean ipOk = clientIp == null || rateLimiter.tryConsume(ipBucket(clientIp), maxAttempts, window);
        if (!emailOk || !ipOk) {
            throw new RateLimitExceededException();
        }
    }

    private void releaseLoginRateLimit(String email, String clientIp) {
        rateLimiter.release(emailBucket(email));
        if (clientIp != null) {
            rateLimiter.release(ipBucket(clientIp));
        }
    }

    private String emailBucket(String email) {
        return "admin_login_email:" + email.toLowerCase(java.util.Locale.ROOT);
    }

    private String ipBucket(String clientIp) {
        return "admin_login_ip:" + clientIp;
    }

    private Duration enrolmentTtl() {
        return config.getMinutes("admin.access.token.ttl.minutes", Duration.ofMinutes(10));
    }
}
