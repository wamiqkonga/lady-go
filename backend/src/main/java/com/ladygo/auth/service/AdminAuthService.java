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
import org.springframework.beans.factory.annotation.Value;
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
    private final AdminLoginThrottle loginThrottle;
    private final boolean mfaRequired;


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
            AuditLogRepository auditLogs,
            AdminLoginThrottle loginThrottle,
            @Value("${ladygo.mfa.required:true}") boolean mfaRequired) {
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
        this.loginThrottle = loginThrottle;
        this.mfaRequired = mfaRequired;
    }

    /**
     * {@code noRollbackFor}: a rejected attempt must still be counted. Every rejection below is thrown before any
     * write other than the counters, so keeping the transaction on those exceptions cannot half-apply a login; if it
     * rolled back, neither the fixed window nor the progressive delay would ever accumulate.
     */
    @Transactional(noRollbackFor = {UnauthorizedException.class, ForbiddenException.class, RateLimitExceededException.class})
    public AdminLoginOutcome login(String email, String password, String totpCode, String deviceId, String clientIp) {
        boolean progressive = config.getBoolean("admin.login.progressive.enabled", false);
        if (progressive) {
            checkProgressiveLimit(email, clientIp);
        } else {
            checkLoginRateLimit(email, clientIp);
        }

        User user = users.findByEmailIgnoreCase(email).orElse(null);
        if (user == null || user.getPasswordHash() == null
                || !passwordEncoder.matches(password, user.getPasswordHash())) {
            recordLoginFailure(progressive, email);
            throw new UnauthorizedException();
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            // Adversarial review finding (CRITICAL): status was never checked on this path.
            throw new ForbiddenException("This account is not active.");
        }

        if (!mfaRequired) {
            // Pilot setting (LADYGO_ADMIN_MFA_REQUIRED=false): staff sign in with email + password only.
            // The admin UI has no password-change or MFA-enrolment screen, so the forced steps would
            // lock every account out; they are skipped along with the second factor.
            user.setLastLoginAt(Instant.now());
            users.save(user);
            releaseLoginRateLimit(email);
            String access = accessTokenIssuer.issue(user.getId(), user.getRole());
            String refresh = refreshTokenService.issue(user.getId(), deviceId);
            return new AdminLoginOutcome.Authenticated(new TokenPair(access, refresh));
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
            releaseLoginRateLimit(email);
            return new AdminLoginOutcome.EnrolmentRequired(token, List.copyOf(steps));
        }

        long acceptedStep;
        try {
            acceptedStep = verifyTotpOrThrow(user, totpCode);
        } catch (UnauthorizedException ex) {
            recordLoginFailure(progressive, email);
            throw ex;
        }

        // The atomic replay-acceptance update runs first and clears the persistence context, so the user is read
        // again before the other write: saving the stale object would merge its old mfaLastUsedStep back over the
        // row. It also means a replayed code is rejected before anything else is written, which is what lets this
        // method keep the counters on rejection.
        if (users.updateMfaLastUsedStepIfNewer(user.getId(), acceptedStep) == 0) {
            log.warn("operation=totp_replay_rejected userId={}", user.getId());
            recordLoginFailure(progressive, email);
            throw new UnauthorizedException();
        }
        User fresh = users.findById(user.getId()).orElseThrow(UnauthorizedException::new);
        fresh.setLastLoginAt(Instant.now());
        users.save(fresh);
        // Adversarial review finding (MEDIUM): a run of legitimate successful logins must not erode
        // the budget meant to catch repeated failures, or enough real logins self-lock the account.
        releaseLoginRateLimit(email);
        String access = accessTokenIssuer.issue(fresh.getId(), fresh.getRole());
        String refresh = refreshTokenService.issue(fresh.getId(), deviceId);
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

    /**
     * Step-up MFA (docs/security/admin-stepup-lockout-recovery-design.md section 2): a signed-in admin re-proves a
     * TOTP code and gets back a fresh access token carrying the step-up time. The same replay guard as login
     * applies, so a code already used for login or a previous step-up must be replaced by the next one.
     */
    /** {@code noRollbackFor}: see {@link #login}; every rejection here happens before any write but the counter. */
    @Transactional(noRollbackFor = {UnauthorizedException.class, ForbiddenException.class, RateLimitExceededException.class})
    public String stepUp(AuthPrincipal principal, String code) {
        requireSignedInStaff(principal);
        String bucket = stepUpBucket(principal.userId());
        Duration window = config.getMinutes("admin.login.window.minutes", Duration.ofMinutes(15));
        if (!rateLimiter.tryConsume(bucket, config.getInt("admin.login.max.attempts", 5), window)) {
            throw new RateLimitExceededException();
        }
        User user = users.findById(principal.userId()).orElseThrow(UnauthorizedException::new);
        if (user.getStatus() != UserStatus.ACTIVE || !user.isMfaEnabled()) {
            throw new UnauthorizedException();
        }
        long acceptedStep = verifyTotpOrThrow(user, code);
        if (users.updateMfaLastUsedStepIfNewer(user.getId(), acceptedStep) == 0) {
            log.warn("operation=totp_replay_rejected userId={}", user.getId());
            throw new UnauthorizedException();
        }
        rateLimiter.release(bucket);
        return accessTokenIssuer.issue(user.getId(), user.getRole(), Instant.now());
    }

    /**
     * Self-service MFA setup for an already signed-in admin who has none (the pilot runs with login MFA off, so
     * the forced-enrolment login path is never reached). Refused once an authenticator exists, so a stolen
     * session cannot swap in the attacker's authenticator; replacing one is the SUPER_ADMIN MFA reset.
     */
    @Transactional
    public MfaEnrollStart startSelfMfaEnrollment(AuthPrincipal principal) {
        requireSignedInStaff(principal);
        User user = users.findById(principal.userId()).orElseThrow(UnauthorizedException::new);
        if (user.isMfaEnabled()) {
            throw new ForbiddenException("An authenticator is already set up for this account.");
        }
        String secret = totpService.generateSecret();
        user.setMfaPendingSecretEnc(mfaSecretCipher.encrypt(secret));
        users.save(user);
        return new MfaEnrollStart(secret, totpService.provisioningUri(user.getEmail(), secret));
    }

    /** Confirms the pending secret with a code and returns a token that already counts as a step-up. */
    @Transactional
    public String confirmSelfMfaEnrollment(AuthPrincipal principal, String code) {
        requireSignedInStaff(principal);
        User user = users.findById(principal.userId()).orElseThrow(UnauthorizedException::new);
        if (user.isMfaEnabled()) {
            throw new ForbiddenException("An authenticator is already set up for this account.");
        }
        if (user.getMfaPendingSecretEnc() == null) {
            throw new UnauthorizedException();
        }
        OptionalLong step = totpService.verify(mfaSecretCipher.decrypt(user.getMfaPendingSecretEnc()), code, Instant.now());
        if (step.isEmpty()) {
            throw new UnauthorizedException();
        }
        user.setMfaSecretEnc(user.getMfaPendingSecretEnc());
        user.setMfaPendingSecretEnc(null);
        user.setMfaEnabled(true);
        users.save(user);
        if (users.updateMfaLastUsedStepIfNewer(user.getId(), step.getAsLong()) == 0) {
            throw new UnauthorizedException();
        }
        return accessTokenIssuer.issue(user.getId(), user.getRole(), Instant.now());
    }

    private void requireSignedInStaff(AuthPrincipal principal) {
        if (principal == null || principal.enrolment() || principal.role() == null) {
            throw new UnauthorizedException();
        }
    }

    private String stepUpBucket(UUID userId) {
        return "admin_stepup:" + userId;
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

    /** Progressive mode: refuse while the email is waiting; the IP bucket stays as a (larger) hard cap. */
    private void checkProgressiveLimit(String email, String clientIp) {
        int ipMax = config.getInt("admin.login.max.attempts", 5) * 4;
        Duration window = config.getMinutes("admin.login.window.minutes", Duration.ofMinutes(15));
        // Consume the IP slot first so an attacker cannot probe for a wait without spending an attempt.
        boolean ipOk = clientIp == null || rateLimiter.tryConsume(ipBucket(clientIp), ipMax, window);
        if (!ipOk || loginThrottle.isLocked(email)) {
            throw new RateLimitExceededException();
        }
    }

    private void recordLoginFailure(boolean progressive, String email) {
        if (progressive) {
            loginThrottle.recordFailure(email);
        }
    }

    /** SUPER_ADMIN unlock: forgets both the progressive wait and the fixed-window counter for one admin. */
    @Transactional
    public void unlockLogin(UUID actorId, UserRole actorRole, UUID targetUserId, String reason, String ip) {
        User target = users.findById(targetUserId).orElseThrow(UnauthorizedException::new);
        if (target.getEmail() != null) {
            loginThrottle.clear(target.getEmail());
            rateLimiter.reset(emailBucket(target.getEmail()));
        }

        AuditLog entry = new AuditLog();
        entry.setActorId(actorId);
        entry.setActorRole(actorRole.name());
        entry.setAction("ADMIN_LOGIN_UNLOCK");
        entry.setTargetType("users");
        entry.setTargetId(targetUserId.toString());
        entry.setMetadata(java.util.Map.of("reason", reason));
        entry.setIp(ip);
        auditLogs.save(entry);
    }

    private void releaseLoginRateLimit(String email) {
        // IP bucket is deliberately never released: it is shared across every email attempted from
        // that IP (office NAT, carrier CGNAT, ...). Releasing it on one account's success would wipe
        // out an attacker's accumulated failed attempts against a DIFFERENT account from the same
        // IP, as soon as any legitimate user on that IP happens to log in - a cross-account rate-limit
        // bypass. Only the email bucket, which is exclusively this account's, is safe to release.
        rateLimiter.release(emailBucket(email));
        loginThrottle.clear(email);
    }

    private String emailBucket(String email) {
        return "admin_login_email:" + AdminLoginThrottle.normalise(email);
    }

    private String ipBucket(String clientIp) {
        return "admin_login_ip:" + clientIp;
    }

    private Duration enrolmentTtl() {
        return config.getMinutes("admin.access.token.ttl.minutes", Duration.ofMinutes(10));
    }
}
