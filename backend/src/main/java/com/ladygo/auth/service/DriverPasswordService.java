package com.ladygo.auth.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import com.ladygo.admin.domain.AuditLog;
import com.ladygo.admin.repository.AuditLogRepository;
import com.ladygo.auth.domain.AdminPasswordReset;
import com.ladygo.auth.repository.AdminPasswordResetRepository;
import com.ladygo.common.exception.ForbiddenException;
import com.ladygo.common.exception.ResourceNotFoundException;
import com.ladygo.common.exception.UnauthorizedException;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.config.ConfigurationService;
import com.ladygo.notifications.email.EmailSender;
import com.ladygo.security.ratelimit.RateLimitExceededException;
import com.ladygo.security.ratelimit.RateLimiter;
import com.ladygo.users.domain.User;
import com.ladygo.users.domain.UserRole;
import com.ladygo.users.domain.UserStatus;
import com.ladygo.users.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Driver (captain) password sign-in, first password, change and emailed reset
 * (docs/development/plans/2026-11-02-driver-registration-amendments.md, docs/security/auth-design.md).
 *
 * <p>Reuses the admin building blocks on purpose: the same {@link PasswordEncoder}, the progressive per-account wait in
 * {@link AdminLoginThrottle} (keys prefixed {@code driver:}), the {@link RateLimiter}, and the single-use hashed
 * emailed token of {@link AdminPasswordResetService}. OTP sign-in does not depend on any of this and keeps working.
 *
 * <p>Every rejection of {@link #login} is thrown without rolling back (see {@code noRollbackFor}) so a rejected attempt
 * still counts; a rolled-back counter would let an attacker guess forever.
 */
@Service
public class DriverPasswordService {

    private static final Logger log = LoggerFactory.getLogger(DriverPasswordService.class);
    private static final String ENABLED_KEY = "auth.driver_password.enabled";
    private static final Duration ATTEMPT_WINDOW = Duration.ofMinutes(15);
    private static final int RESET_IP_ATTEMPTS = 20;
    private static final int CHANGE_ATTEMPTS = 5;
    private static final ExecutorService WORKER = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(200), runnable -> {
                Thread thread = new Thread(runnable, "driver-password-reset");
                thread.setDaemon(true);
                return thread;
            });

    private final UserRepository users;
    private final AdminPasswordResetRepository resets;
    private final AuditLogRepository auditLogs;
    private final RefreshTokenService refreshTokenService;
    private final AccessTokenIssuer accessTokenIssuer;
    private final PasswordEncoder passwordEncoder;
    private final DriverPasswordPolicy policy;
    private final AdminLoginThrottle throttle;
    private final RateLimiter rateLimiter;
    private final ConfigurationService config;
    private final EmailSender emailSender;
    private final TransactionTemplate tx;
    private final DriverApprovalGate approvalGate;
    private final String websiteBaseUrl;
    /** Verified against when the account does not exist, so an unknown account costs the same time as a real one. */
    private final String dummyHash;

    public DriverPasswordService(
            UserRepository users, AdminPasswordResetRepository resets, AuditLogRepository auditLogs,
            RefreshTokenService refreshTokenService, AccessTokenIssuer accessTokenIssuer,
            PasswordEncoder passwordEncoder, DriverPasswordPolicy policy, AdminLoginThrottle throttle,
            RateLimiter rateLimiter, ConfigurationService config, ObjectProvider<EmailSender> emailSenders,
            TransactionTemplate tx, DriverApprovalGate approvalGate,
            @Value("${ladygo.website.base-url:}") String websiteBaseUrl) {
        this.users = users;
        this.resets = resets;
        this.auditLogs = auditLogs;
        this.refreshTokenService = refreshTokenService;
        this.accessTokenIssuer = accessTokenIssuer;
        this.passwordEncoder = passwordEncoder;
        this.policy = policy;
        this.throttle = throttle;
        this.rateLimiter = rateLimiter;
        this.config = config;
        this.tx = tx;
        this.approvalGate = approvalGate;
        this.emailSender = emailSenders.getIfAvailable();
        this.websiteBaseUrl = AdminPasswordResetService.validatedBaseUrl(websiteBaseUrl);
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
        if (this.emailSender == null || this.websiteBaseUrl.isEmpty()) {
            log.warn("operation=driver_password_reset_disabled reason={} - the driver forgot-password link cannot be "
                    + "sent until an email provider and LADYGO_WEBSITE_BASE_URL are configured",
                    this.emailSender == null ? "no_email_sender" : "no_website_base_url");
        }
    }

    public boolean hasPassword(UUID userId) {
        return users.findById(userId).map(u -> u.getPasswordHash() != null).orElse(false);
    }

    // ---- sign in ---------------------------------------------------------------------------------------------

    @Transactional(noRollbackFor = {UnauthorizedException.class, ForbiddenException.class, RateLimitExceededException.class})
    public TokenPair login(String identifier, String password, String deviceId, String clientIp) {
        return signIn(identifier, password, deviceId, clientIp, false);
    }

    /** The driver app's sign-in: the same checks as {@link #login}, then only an approved driver gets tokens. */
    @Transactional(noRollbackFor = {UnauthorizedException.class, ForbiddenException.class, RateLimitExceededException.class,
            DriverLoginBlockedException.class})
    public TokenPair loginForApp(String identifier, String password, String deviceId, String clientIp) {
        return signIn(identifier, password, deviceId, clientIp, true);
    }

    private TokenPair signIn(String identifier, String password, String deviceId, String clientIp, boolean appOnly) {
        requireEnabled();
        int perIp = config.getInt("driver.password.login.max.per.ip", 20);
        if (clientIp != null && !rateLimiter.tryConsume("driver_pwlogin_ip:" + clientIp, perIp, ATTEMPT_WINDOW)) {
            throw new RateLimitExceededException();
        }
        User user = findDriver(identifier);
        // One key per real account (not per spelling), so trying the phone and then the email cannot dodge the wait.
        String key = user != null ? "driver:" + user.getId() : "driver:" + normaliseIdentifier(identifier);
        if (throttle.isLocked(key)) {
            throw new RateLimitExceededException();
        }
        boolean ok;
        if (user == null || user.getPasswordHash() == null) {
            passwordEncoder.matches(password, dummyHash);
            ok = false;
        } else {
            ok = passwordEncoder.matches(password, user.getPasswordHash());
        }
        if (!ok) {
            throttle.recordFailure(key);
            throw new UnauthorizedException();
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ForbiddenException("This account is not active.");
        }
        throttle.clear(key);
        if (clientIp != null) {
            rateLimiter.release("driver_pwlogin_ip:" + clientIp);
        }
        if (appOnly) {
            approvalGate.requireApproved(user);
        }
        user.setLastLoginAt(Instant.now());
        users.save(user);
        log.info("operation=driver_password_login result=success userId={}", user.getId());
        return new TokenPair(accessTokenIssuer.issue(user.getId(), user.getRole()),
                refreshTokenService.issue(user.getId(), deviceId));
    }

    // ---- first password and change ----------------------------------------------------------------------------

    /**
     * First password for a signed-in driver. Only allowed shortly after a real sign-in (OTP or password), so an old or
     * stolen access token cannot plant a password. Nothing is revoked: there was no earlier password to protect.
     */
    @Transactional
    public void setFirstPassword(UUID userId, String newPassword, String clientIp) {
        requireEnabled();
        User user = activeDriver(userId);
        if (user.getPasswordHash() != null) {
            throw new ForbiddenException("A password is already set. Use change password instead.");
        }
        Duration fresh = config.getMinutes("driver.password.set.fresh.minutes", Duration.ofMinutes(10));
        if (user.getLastLoginAt() == null || user.getLastLoginAt().isBefore(Instant.now().minus(fresh))) {
            throw new ForbiddenException("Please sign in again with your code, then set your password.");
        }
        policy.validate(newPassword, user);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordSetAt(Instant.now());
        users.save(user);
        audit(user, "DRIVER_PASSWORD_SET", clientIp);
        log.info("operation=driver_password_set userId={}", user.getId());
        notifyChanged(user, "set");
    }

    /** Needs the current password (its attempts are capped and counted even when rejected) and ends every session. */
    @Transactional(noRollbackFor = {UnauthorizedException.class, RateLimitExceededException.class})
    public TokenPair changePassword(UUID userId, String currentPassword, String newPassword, String deviceId,
                                    String clientIp) {
        requireEnabled();
        User user = activeDriver(userId);
        String bucket = "driver_pwchange:" + userId;
        if (!rateLimiter.tryConsume(bucket, CHANGE_ATTEMPTS, ATTEMPT_WINDOW)) {
            throw new RateLimitExceededException();
        }
        if (user.getPasswordHash() == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new UnauthorizedException();
        }
        rateLimiter.release(bucket);
        policy.validate(newPassword, user);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordSetAt(Instant.now());
        users.save(user);
        refreshTokenService.revokeAll(user.getId());
        audit(user, "DRIVER_PASSWORD_CHANGED", clientIp);
        log.info("operation=driver_password_changed userId={}", user.getId());
        notifyChanged(user, "changed");
        return new TokenPair(accessTokenIssuer.issue(user.getId(), user.getRole()),
                refreshTokenService.issue(user.getId(), deviceId));
    }

    // ---- forgot / reset ---------------------------------------------------------------------------------------

    /**
     * Always returns normally and does the same work on the request thread for every input (two counter updates and
     * one hand-off), so neither the reply nor its timing depends on whether the account exists.
     */
    public void requestReset(String identifier, String clientIp) {
        String normalised = normaliseIdentifier(identifier);
        int max = config.getInt("driver.password_reset.max.requests", 3);
        Duration window = config.getMinutes("driver.password_reset.window.minutes", Duration.ofMinutes(60));
        boolean idOk = rateLimiter.tryConsume("driver_pwreset_req_id:" + normalised, max, window);
        boolean ipOk = clientIp == null || rateLimiter.tryConsume("driver_pwreset_req_ip:" + clientIp, max * 4, window);
        if (!idOk || !ipOk) {
            log.info("operation=driver_password_reset_request result=rate_limited");
            return;
        }
        if (!config.getBoolean(ENABLED_KEY, true)) {
            return;
        }
        Duration ttl = config.getMinutes("driver.password_reset.ttl.minutes", Duration.ofMinutes(30));
        try {
            WORKER.execute(() -> issueLink(identifier, ttl));
        } catch (RejectedExecutionException ex) {
            log.warn("operation=driver_password_reset_request result=dropped reason=queue_full");
        }
    }

    private void issueLink(String identifier, Duration ttl) {
        try {
            User user = findDriver(identifier);
            if (user == null || user.getStatus() != UserStatus.ACTIVE || user.getEmail() == null) {
                log.info("operation=driver_password_reset_request result=no_eligible_account");
                return;
            }
            if (emailSender == null || websiteBaseUrl.isEmpty()) {
                return; // warned at startup; nothing can be delivered.
            }
            String token = AdminPasswordResetService.newToken();
            AdminPasswordReset reset = new AdminPasswordReset();
            reset.setUserId(user.getId());
            reset.setTokenHash(AdminPasswordResetService.sha256Hex(token));
            reset.setExpiresAt(Instant.now().plus(ttl));
            resets.save(reset);
            // The token is in the URL fragment so it never reaches server logs or Referer headers.
            String link = websiteBaseUrl + "/captain/reset-password#token=" + token;
            send(user, "Reset your Lady Go captain password",
                    "Someone asked to reset the password for this Lady Go captain account.\n\n"
                            + "Open this link within " + ttl.toMinutes() + " minutes to choose a new password:\n" + link
                            + "\n\nIf you did not ask for this, ignore this email - your password has not changed. "
                            + "Never share this link.", "link");
        } catch (RuntimeException ex) {
            log.warn("operation=driver_password_reset_request result=failed error={}", ex.getClass().getSimpleName());
        }
    }

    /**
     * Not one big transaction: the IP attempt counter must survive a rejected attempt. Checks come first; only the final
     * changes run together. Success ends every session, closes other open links and tells the captain by email.
     */
    public void resetPassword(String token, String newPassword, String clientIp) {
        requireEnabled();
        if (clientIp != null && !rateLimiter.tryConsume("driver_pwreset_ip:" + clientIp, RESET_IP_ATTEMPTS, ATTEMPT_WINDOW)) {
            throw new RateLimitExceededException();
        }
        AdminPasswordReset reset = resets.findByTokenHash(AdminPasswordResetService.sha256Hex(token))
                .orElseThrow(UnauthorizedException::new);
        if (reset.getUsedAt() != null || !reset.getExpiresAt().isAfter(Instant.now())) {
            throw new UnauthorizedException();
        }
        User user = users.findById(reset.getUserId()).orElseThrow(UnauthorizedException::new);
        if (user.getRole() != UserRole.DRIVER || user.getStatus() != UserStatus.ACTIVE) {
            throw new UnauthorizedException();
        }
        policy.validate(newPassword, user);
        String encoded = passwordEncoder.encode(newPassword);
        User changed = tx.execute(status -> applyReset(reset.getId(), user.getId(), encoded, clientIp));
        log.info("operation=driver_password_reset result=success userId={}", changed.getId());
        notifyChanged(changed, "reset");
    }

    private User applyReset(UUID resetId, UUID userId, String encoded, String clientIp) {
        if (resets.consumeIfActive(resetId, Instant.now()) == 0) {
            throw new UnauthorizedException(); // another request used or expired the link first.
        }
        User fresh = users.findById(userId).orElseThrow(UnauthorizedException::new);
        fresh.setPasswordHash(encoded);
        fresh.setPasswordSetAt(Instant.now());
        users.save(fresh);
        refreshTokenService.revokeAll(fresh.getId());
        resets.invalidateOpenForUser(fresh.getId(), Instant.now());
        // A reset also forgives earlier wrong guesses: the owner has just proved they control the email.
        throttle.clear("driver:" + fresh.getId());
        audit(fresh, "DRIVER_PASSWORD_RESET", clientIp);
        return fresh;
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private void requireEnabled() {
        if (!config.getBoolean(ENABLED_KEY, true)) {
            throw new ResourceNotFoundException("Password sign-in is not available right now.");
        }
    }

    private User activeDriver(UUID userId) {
        User user = users.findById(userId).orElseThrow(UnauthorizedException::new);
        if (user.getRole() != UserRole.DRIVER) {
            throw new ForbiddenException("Only captains can set a captain password.");
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ForbiddenException("This account is not active.");
        }
        return user;
    }

    /** Phone (as typed, spaces and dashes ignored, a plain 10-digit number gets +91 like the website) or email. */
    private User findDriver(String identifier) {
        String id = identifier == null ? "" : identifier.trim();
        User found;
        if (id.contains("@")) {
            found = users.findByEmailIgnoreCase(id).orElse(null);
        } else {
            found = users.findByPhone(normalisePhone(id)).orElse(null);
        }
        return found != null && found.getRole() == UserRole.DRIVER ? found : null;
    }

    static String normalisePhone(String raw) {
        String compact = raw.replaceAll("[\\s\\-()]", "");
        if (compact.matches("\\d{10}")) {
            return "+91" + compact;
        }
        return compact;
    }

    private static String normaliseIdentifier(String identifier) {
        String id = identifier == null ? "" : identifier.trim();
        return id.contains("@") ? id.toLowerCase(Locale.ROOT) : normalisePhone(id);
    }

    private void audit(User user, String action, String clientIp) {
        AuditLog entry = new AuditLog();
        entry.setActorId(user.getId());
        entry.setActorRole(user.getRole().name());
        entry.setAction(action);
        entry.setTargetType("users");
        entry.setTargetId(user.getId().toString());
        entry.setIp(clientIp);
        auditLogs.save(entry);
    }

    private void notifyChanged(User user, String what) {
        if (emailSender == null || user.getEmail() == null) {
            return;
        }
        send(user, "Your Lady Go password was " + what,
                "Your Lady Go captain password was " + what
                        + (what.equals("set") ? "." : " and you were signed out everywhere.")
                        + "\n\nIf this was not you, write to supportladygo@gmail.com straight away.", "changed_notice");
    }

    /** Sends and logs what really happened ({@code send} reports failure by returning false). Never logs the address. */
    private void send(User user, String subject, String body, String kind) {
        try {
            boolean sent = emailSender.send(user.getEmail(), subject, body);
            log.info("operation=driver_password_email kind={} result={} userId={}", kind, sent ? "sent" : "failed", user.getId());
        } catch (RuntimeException ex) {
            log.warn("operation=driver_password_email kind={} result=failed userId={} error={}", kind, user.getId(),
                    ex.getClass().getSimpleName());
        }
    }
}
