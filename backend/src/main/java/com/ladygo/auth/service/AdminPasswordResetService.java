package com.ladygo.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import com.ladygo.admin.domain.AuditLog;
import com.ladygo.admin.repository.AuditLogRepository;
import com.ladygo.auth.domain.AdminPasswordReset;
import com.ladygo.auth.repository.AdminPasswordResetRepository;
import com.ladygo.common.exception.UnauthorizedException;
import com.ladygo.config.ConfigurationService;
import com.ladygo.notifications.email.EmailSender;
import com.ladygo.security.crypto.MfaSecretCipher;
import com.ladygo.security.crypto.TotpService;
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
import org.springframework.transaction.support.TransactionTemplate;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * Admin password recovery by emailed link (docs/security/admin-stepup-lockout-recovery-design.md section 4).
 *
 * <p>Security properties: the reply to a request never depends on whether the address is a known admin; the token is
 * 32 random bytes of which only the SHA-256 is stored; a link is single-use and short-lived; the token travels in the
 * URL fragment so it never reaches server logs or Referer headers; resetting needs a current TOTP code when the account
 * has an authenticator; success signs every session out and tells the owner by email.
 */
@Service
public class AdminPasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(AdminPasswordResetService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int RESET_ATTEMPTS_PER_LINK = 5;
    private static final int IP_ATTEMPTS = 20;
    private static final Duration ATTEMPT_WINDOW = Duration.ofMinutes(15);
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);
    /** One worker keeps the work in request order and bounded; a full queue drops the job (the user can ask again). */
    private static final ExecutorService WORKER = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(200), runnable -> {
                Thread thread = new Thread(runnable, "admin-password-reset");
                thread.setDaemon(true);
                return thread;
            });

    private final UserRepository users;
    private final AdminPasswordResetRepository resets;
    private final AuditLogRepository auditLogs;
    private final RefreshTokenService refreshTokenService;
    private final PasswordEncoder passwordEncoder;
    private final TotpService totpService;
    private final MfaSecretCipher mfaSecretCipher;
    private final RateLimiter rateLimiter;
    private final ConfigurationService config;
    private final EmailSender emailSender;
    private final String adminWebBaseUrl;
    private final TransactionTemplate tx;

    public AdminPasswordResetService(
            UserRepository users, AdminPasswordResetRepository resets, AuditLogRepository auditLogs,
            RefreshTokenService refreshTokenService, PasswordEncoder passwordEncoder, TotpService totpService,
            MfaSecretCipher mfaSecretCipher, RateLimiter rateLimiter, ConfigurationService config,
            ObjectProvider<EmailSender> emailSenders, TransactionTemplate tx,
            @Value("${ladygo.admin.web-base-url:}") String adminWebBaseUrl) {
        this.users = users;
        this.resets = resets;
        this.auditLogs = auditLogs;
        this.refreshTokenService = refreshTokenService;
        this.passwordEncoder = passwordEncoder;
        this.totpService = totpService;
        this.mfaSecretCipher = mfaSecretCipher;
        this.rateLimiter = rateLimiter;
        this.config = config;
        this.tx = tx;
        this.emailSender = emailSenders.getIfAvailable();
        this.adminWebBaseUrl = validatedBaseUrl(adminWebBaseUrl);
        if (this.emailSender == null || this.adminWebBaseUrl.isEmpty()) {
            log.warn("operation=admin_password_reset_disabled reason={} - the forgot-password link cannot be sent until "
                    + "an email provider and LADYGO_ADMIN_WEB_BASE_URL are configured",
                    this.emailSender == null ? "no_email_sender" : "no_admin_web_base_url");
        }
    }

    /**
     * Always returns normally and does the same work on the request thread for every input (two counter updates and
     * one hand-off), so neither the reply nor its timing depends on whether the address is a known admin. Looking the
     * account up, storing the link and sending the email happen on a worker.
     */
    public void requestReset(String email, String clientIp) {
        String normalised = email.trim().toLowerCase(Locale.ROOT);
        int maxRequests = config.getInt("admin.password_reset.max.requests", 3);
        Duration window = config.getMinutes("admin.password_reset.window.minutes", Duration.ofMinutes(60));
        boolean emailOk = rateLimiter.tryConsume("admin_pwreset_req_email:" + normalised, maxRequests, window);
        boolean ipOk = clientIp == null || rateLimiter.tryConsume("admin_pwreset_req_ip:" + clientIp, maxRequests * 4, window);
        if (!emailOk || !ipOk) {
            log.info("operation=admin_password_reset_request result=rate_limited");
            return;
        }
        Duration ttl = config.getMinutes("admin.password_reset.ttl.minutes", DEFAULT_TTL);
        try {
            WORKER.execute(() -> issueLink(normalised, ttl));
        } catch (RejectedExecutionException ex) {
            log.warn("operation=admin_password_reset_request result=dropped reason=queue_full");
        }
    }

    /** Worker side of {@link #requestReset}. Earlier open links are left alone: each is single-use and expires, and
     *  cancelling them would let anyone who knows an address keep its owner from ever using the link they were sent. */
    private void issueLink(String normalisedEmail, Duration ttl) {
        try {
            User user = users.findByEmailIgnoreCase(normalisedEmail).orElse(null);
            if (user == null || !isStaff(user.getRole()) || user.getStatus() != UserStatus.ACTIVE
                    || user.getPasswordHash() == null) {
                log.info("operation=admin_password_reset_request result=no_eligible_account");
                return;
            }
            if (emailSender == null || adminWebBaseUrl.isEmpty()) {
                return; // already warned at startup; nothing can be delivered.
            }
            storeAndEmailLink(user, ttl, "Reset your Lady Go admin password",
                    "Someone asked to reset the password for this Lady Go admin account.\n\n"
                            + "Open this link within " + ttl.toMinutes() + " minutes to choose a new password:\n%s\n\n"
                            + "If you did not ask for this, ignore this email - your password has not changed. "
                            + "Never share this link.",
                    "link");
        } catch (RuntimeException ex) {
            log.warn("operation=admin_password_reset_request result=failed error={}", ex.getClass().getSimpleName());
        }
    }

    /** True when a link could actually be delivered (an email provider and the admin portal address are configured). */
    public boolean canDeliverLinks() {
        return emailSender != null && !adminWebBaseUrl.isEmpty();
    }

    /**
     * Staff invitation: the same hashed, single-use, expiring token and the same reset page as password recovery, so
     * an invitee sets a first password exactly the way a forgetful admin sets a new one. Called inside the caller's
     * transaction; if the email is not accepted the caller's work is rolled back, so no account is left that nobody
     * can enter. Only the SHA-256 of the token is stored; the token is never returned or logged.
     */
    public void sendInvite(User invitee, Duration ttl) {
        if (!canDeliverLinks() || !storeAndEmailLink(invitee, ttl, "You are invited to the Lady Go admin portal",
                "You have been invited to the Lady Go admin portal as " + invitee.getRole().name() + ".\n\n"
                        + "Open this link within " + ttl.toMinutes() / 60 + " hours to choose your password:\n%s\n\n"
                        + "If you were not expecting this, ignore this email. Never share this link.",
                "invite")) {
            throw new com.ladygo.common.exception.EmailDeliveryException();
        }
    }

    /** Stores a fresh hashed token for {@code user} and emails the link; {@code %s} in {@code bodyTemplate} is the link.
     *  The token travels in the URL fragment so it never reaches server logs or Referer headers. */
    private boolean storeAndEmailLink(User user, Duration ttl, String subject, String bodyTemplate, String kind) {
        String token = newToken();
        AdminPasswordReset reset = new AdminPasswordReset();
        reset.setUserId(user.getId());
        reset.setTokenHash(sha256Hex(token));
        reset.setExpiresAt(Instant.now().plus(ttl));
        resets.save(reset);
        String link = adminWebBaseUrl + "/reset-password#token=" + token;
        return deliver(user.getId(), user.getEmail(), subject, bodyTemplate.formatted(link), kind);
    }

    /**
     * Not one big transaction: the attempt counters must survive a rejected attempt, and a rolled-back transaction would
     * undo them, leaving authenticator codes guessable without limit. Everything that is only checked (counters, link,
     * code) happens first; only the final changes run together in a transaction.
     */
    public void resetPassword(String token, String newPassword, String totpCode, String clientIp) {
        if (clientIp != null && !rateLimiter.tryConsume("admin_pwreset_ip:" + clientIp, IP_ATTEMPTS, ATTEMPT_WINDOW)) {
            throw new RateLimitExceededException();
        }
        AdminPasswordReset reset = resets.findByTokenHash(sha256Hex(token)).orElseThrow(UnauthorizedException::new);
        if (reset.getUsedAt() != null || !reset.getExpiresAt().isAfter(Instant.now())) {
            throw new UnauthorizedException();
        }

        // Guessing the TOTP code against one link is capped per link; a correct reset releases the budget.
        String attemptBucket = "admin_pwreset_link:" + reset.getId();
        if (!rateLimiter.tryConsume(attemptBucket, RESET_ATTEMPTS_PER_LINK, ATTEMPT_WINDOW)) {
            throw new RateLimitExceededException();
        }

        User user = users.findById(reset.getUserId()).orElseThrow(UnauthorizedException::new);
        if (user.getStatus() != UserStatus.ACTIVE || !isStaff(user.getRole())) {
            throw new UnauthorizedException();
        }

        long acceptedStep = -1;
        if (user.isMfaEnabled()) {
            if (totpCode == null || user.getMfaSecretEnc() == null) {
                throw new UnauthorizedException();
            }
            OptionalLong step = totpService.verify(mfaSecretCipher.decrypt(user.getMfaSecretEnc()), totpCode, Instant.now());
            if (step.isEmpty()) {
                throw new UnauthorizedException();
            }
            acceptedStep = step.getAsLong();
        }

        long step = acceptedStep;
        String encoded = passwordEncoder.encode(newPassword);
        User changed = tx.execute(status -> applyReset(reset.getId(), user.getId(), encoded, step, attemptBucket, clientIp));
        log.info("operation=admin_password_reset result=success userId={}", changed.getId());
        // After the commit, so a failed commit never tells the owner their password changed.
        if (emailSender != null) {
            deliver(changed.getId(), changed.getEmail(), "Your Lady Go admin password was changed",
                    "Your Lady Go admin password was changed and every signed-in session was ended.\n\n"
                            + "If this was not you, contact another administrator straight away.",
                    "changed_notice");
        }
    }

    /** The all-or-nothing part: any rejection here rolls back the password, the link and the session revocation. */
    private User applyReset(java.util.UUID resetId, java.util.UUID userId, String encodedPassword, long acceptedStep,
                            String attemptBucket, String clientIp) {
        if (resets.consumeIfActive(resetId, Instant.now()) == 0) {
            throw new UnauthorizedException(); // another request used or expired the link first.
        }
        // consumeIfActive clears the persistence context, so this is a fresh managed row.
        User fresh = users.findById(userId).orElseThrow(UnauthorizedException::new);
        fresh.setPasswordHash(encodedPassword);
        fresh.setMustChangePassword(false);
        fresh.setMfaPendingSecretEnc(null); // an unfinished enrolment from before the reset must not survive it.
        users.save(fresh);
        if (acceptedStep >= 0 && users.updateMfaLastUsedStepIfNewer(fresh.getId(), acceptedStep) == 0) {
            throw new UnauthorizedException(); // replayed code: roll the whole reset back.
        }

        refreshTokenService.revokeAll(fresh.getId());
        resets.invalidateOpenForUser(fresh.getId(), Instant.now());
        rateLimiter.release(attemptBucket);

        AuditLog entry = new AuditLog();
        entry.setActorId(fresh.getId());
        entry.setActorRole(fresh.getRole().name());
        entry.setAction("ADMIN_PASSWORD_RESET");
        entry.setTargetType("users");
        entry.setTargetId(fresh.getId().toString());
        entry.setIp(clientIp);
        auditLogs.save(entry);
        return fresh;
    }

    /** Sends and then logs what really happened: {@code send} reports failure by returning false, not by throwing. */
    private boolean deliver(Object userId, String to, String subject, String body, String kind) {
        boolean sent;
        try {
            sent = emailSender.send(to, subject, body);
        } catch (RuntimeException ex) {
            // Class name only: the message can echo the recipient.
            log.warn("operation=admin_password_reset_email kind={} result=failed userId={} error={}", kind, userId,
                    ex.getClass().getSimpleName());
            return false;
        }
        if (sent) {
            log.info("operation=admin_password_reset_email kind={} result=sent userId={}", kind, userId);
        } else {
            log.warn("operation=admin_password_reset_email kind={} result=failed userId={}", kind, userId);
        }
        return sent;
    }

    private static boolean isStaff(UserRole role) {
        return role == UserRole.ADMIN || role == UserRole.SUPER_ADMIN || role == UserRole.SUPPORT;
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Blank = feature off. Otherwise https (or plain http only for exactly localhost / 127.0.0.1), no trailing slash. */
    private static String validatedBaseUrl(String raw) {
        String url = raw == null ? "" : raw.trim();
        if (url.isEmpty()) {
            return "";
        }
        String host;
        String scheme;
        try {
            URI uri = new URI(url);
            host = uri.getHost();
            scheme = uri.getScheme();
        } catch (URISyntaxException ex) {
            throw new IllegalStateException("ladygo.admin.web-base-url (LADYGO_ADMIN_WEB_BASE_URL) is not a valid URL.");
        }
        boolean local = "http".equals(scheme) && ("localhost".equals(host) || "127.0.0.1".equals(host));
        if (!"https".equals(scheme) && !local) {
            throw new IllegalStateException("ladygo.admin.web-base-url (LADYGO_ADMIN_WEB_BASE_URL) must be https.");
        }
        if (host == null) {
            throw new IllegalStateException("ladygo.admin.web-base-url (LADYGO_ADMIN_WEB_BASE_URL) needs a host name.");
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
