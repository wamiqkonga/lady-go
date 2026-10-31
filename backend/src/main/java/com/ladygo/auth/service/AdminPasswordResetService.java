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
import java.util.concurrent.CompletableFuture;
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
import org.springframework.transaction.annotation.Transactional;

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

    public AdminPasswordResetService(
            UserRepository users, AdminPasswordResetRepository resets, AuditLogRepository auditLogs,
            RefreshTokenService refreshTokenService, PasswordEncoder passwordEncoder, TotpService totpService,
            MfaSecretCipher mfaSecretCipher, RateLimiter rateLimiter, ConfigurationService config,
            ObjectProvider<EmailSender> emailSenders,
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
        this.emailSender = emailSenders.getIfAvailable();
        this.adminWebBaseUrl = validatedBaseUrl(adminWebBaseUrl);
        if (this.emailSender == null || this.adminWebBaseUrl.isEmpty()) {
            log.warn("operation=admin_password_reset_disabled reason={} - the forgot-password link cannot be sent until "
                    + "an email provider and LADYGO_ADMIN_WEB_BASE_URL are configured",
                    this.emailSender == null ? "no_email_sender" : "no_admin_web_base_url");
        }
    }

    /** Always returns normally: the caller must answer identically whether or not an email was sent. */
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

        User user = users.findByEmailIgnoreCase(normalised).orElse(null);
        String token = newToken();
        String tokenHash = sha256Hex(token); // computed for unknown addresses too, so the work done is similar.
        if (user == null || !isStaff(user.getRole()) || user.getStatus() != UserStatus.ACTIVE
                || user.getPasswordHash() == null) {
            log.info("operation=admin_password_reset_request result=no_eligible_account");
            return;
        }

        resets.invalidateOpenForUser(user.getId(), Instant.now());
        AdminPasswordReset reset = new AdminPasswordReset();
        reset.setUserId(user.getId());
        reset.setTokenHash(tokenHash);
        reset.setExpiresAt(Instant.now().plus(config.getMinutes("admin.password_reset.ttl.minutes", Duration.ofMinutes(30))));
        resets.save(reset);

        if (emailSender == null || adminWebBaseUrl.isEmpty()) {
            return; // already warned at startup; nothing can be delivered.
        }
        String link = adminWebBaseUrl + "/reset-password#token=" + token;
        sendAsync(user.getEmail(), "Reset your Lady Go admin password",
                "Someone asked to reset the password for this Lady Go admin account.\n\n"
                        + "Open this link within 30 minutes to choose a new password:\n" + link + "\n\n"
                        + "If you did not ask for this, ignore this email - your password has not changed. "
                        + "Never share this link.");
        log.info("operation=admin_password_reset_request result=link_sent userId={}", user.getId());
    }

    @Transactional
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

        if (resets.consumeIfActive(reset.getId(), Instant.now()) == 0) {
            throw new UnauthorizedException(); // another request used or expired the link first.
        }

        // Re-read: consumeIfActive clears the persistence context, so this is a fresh managed row.
        User fresh = users.findById(user.getId()).orElseThrow(UnauthorizedException::new);
        fresh.setPasswordHash(passwordEncoder.encode(newPassword));
        fresh.setMustChangePassword(false);
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

        if (emailSender != null) {
            sendAsync(fresh.getEmail(), "Your Lady Go admin password was changed",
                    "Your Lady Go admin password was changed and every signed-in session was ended.\n\n"
                            + "If this was not you, contact another administrator straight away.");
        }
        log.info("operation=admin_password_reset result=success userId={}", fresh.getId());
    }

    private void sendAsync(String to, String subject, String body) {
        CompletableFuture.runAsync(() -> {
            try {
                emailSender.send(to, subject, body);
            } catch (RuntimeException ex) {
                log.warn("operation=admin_password_reset_email result=failed", ex);
            }
        });
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

    /** Blank = feature off. Otherwise https (or plain http only for localhost), with no trailing slash. */
    private static String validatedBaseUrl(String raw) {
        String url = raw == null ? "" : raw.trim();
        if (url.isEmpty()) {
            return "";
        }
        boolean local = url.startsWith("http://localhost") || url.startsWith("http://127.0.0.1");
        if (!url.startsWith("https://") && !local) {
            throw new IllegalStateException("ladygo.admin.web-base-url (LADYGO_ADMIN_WEB_BASE_URL) must be https.");
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
