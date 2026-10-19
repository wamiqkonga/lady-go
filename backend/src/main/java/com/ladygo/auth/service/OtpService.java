package com.ladygo.auth.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import com.ladygo.auth.domain.OtpPurpose;
import com.ladygo.auth.domain.OtpRequest;
import com.ladygo.auth.repository.OtpRequestRepository;
import com.ladygo.config.ConfigurationService;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.notifications.email.EmailSender;
import com.ladygo.notifications.sms.SmsSender;
import com.ladygo.security.crypto.OtpHasher;
import com.ladygo.security.ratelimit.RateLimitExceededException;
import com.ladygo.security.ratelimit.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OTP request/verify (docs/security/auth-design.md section 4). Request and verify are each a single
 * transaction built on atomic conditional updates in {@link OtpRequestRepository}, so concurrent calls
 * cannot double-count attempts or double-consume a code.
 */
@Service
public class OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpService.class);
    private static final int CODE_DIGITS = 6;

    private final OtpRequestRepository otpRequests;
    private final OtpHasher otpHasher;
    private final SmsSender smsSender;
    private final EmailSender emailSender;
    private final boolean emailChannel;
    private final RateLimiter rateLimiter;
    private final ConfigurationService config;
    private final SecureRandom random = new SecureRandom();

    public OtpService(
            OtpRequestRepository otpRequests,
            OtpHasher otpHasher,
            SmsSender smsSender,
            ObjectProvider<EmailSender> emailSenders,
            @Value("${ladygo.otp.channel:sms}") String channel,
            RateLimiter rateLimiter,
            ConfigurationService config) {
        this.otpRequests = otpRequests;
        this.otpHasher = otpHasher;
        this.smsSender = smsSender;
        this.emailChannel = "email".equalsIgnoreCase(channel);
        this.emailSender = emailSenders.getIfAvailable();
        if (emailChannel && emailSender == null) {
            throw new IllegalStateException("ladygo.otp.channel=email but no EmailSender is configured.");
        }
        this.rateLimiter = rateLimiter;
        this.config = config;
    }

    /** True when codes are delivered by email (MVP 1, {@code ladygo.otp.channel=email}) instead of SMS. */
    public boolean isEmailChannel() {
        return emailChannel;
    }

    /** Overload for callers with no client IP available (e.g. tests, internal callers) - the per-IP
     *  send cap below is simply skipped, every other check still applies. */
    @Transactional
    public void requestOtp(String phone, OtpPurpose purpose) {
        requestOtp(phone, purpose, null);
    }

    /** {@code ip}: the caller's resolved client IP (see {@code ClientIpResolver}), for the per-IP send
     *  cap below - never trust a raw unresolved header here. */
    @Transactional
    public void requestOtp(String phone, OtpPurpose purpose, String ip) {
        requestOtp(phone, purpose, ip, null);
    }

    /** {@code email}: required (and used as the delivery address) only when the email channel is on;
     *  ignored for SMS. Stored lower-cased on the OTP row so a successful verify can bind it to the user. */
    @Transactional
    public void requestOtp(String phone, OtpPurpose purpose, String ip, String email) {
        if (emailChannel && (email == null || email.isBlank())) {
            throw new ValidationException("Email is required.");
        }
        String bucket = "otp_request:" + phone + ":" + purpose;
        int maxPerHour = config.getInt("otp.resend.max.per.hour", 5);
        if (!rateLimiter.tryConsume(bucket, maxPerHour, Duration.ofHours(1))) {
            throw new RateLimitExceededException();
        }
        Duration cooldown = config.getSeconds("otp.resend.cooldown.seconds", Duration.ofSeconds(30));
        if (!rateLimiter.tryConsume(bucket + ":cooldown", 1, cooldown)) {
            throw new RateLimitExceededException();
        }
        checkSendCaps(phone, ip);
        String deliveryEmail = emailChannel ? email.trim().toLowerCase() : null;
        if (deliveryEmail != null) {
            int maxPerAddress = config.getInt("email.send.max_per_address.daily", 20);
            if (!rateLimiter.tryConsume("email_send:address:" + deliveryEmail, maxPerAddress, Duration.ofDays(1))) {
                throw new RateLimitExceededException();
            }
        }

        Instant now = Instant.now();
        otpRequests.invalidateUnconsumed(phone, purpose.name(), now);

        String code = generateCode();
        Duration expiry = config.getSeconds("otp.expiry.seconds", Duration.ofMinutes(5));
        OtpRequest entity = new OtpRequest();
        entity.setPhone(phone);
        entity.setEmail(deliveryEmail);
        entity.setPurpose(purpose);
        entity.setCodeHash(otpHasher.hash(code));
        entity.setExpiresAt(now.plus(expiry));
        otpRequests.save(entity);

        long minutes = Math.max(expiry.toMinutes(), 1);
        if (deliveryEmail != null) {
            emailSender.send(deliveryEmail, "Your Lady Go verification code",
                    "Your Lady Go verification code is " + code + ".\nIt is valid for " + minutes
                            + " minutes. If you did not request it, you can ignore this email.");
            return;
        }
        smsSender.send(phone, "otp_code", Map.of("code", code, "validForMinutes", String.valueOf(minutes)));
    }

    /** Separate from the per-phone/purpose resend limiter above, which bounds how often ONE number can
     *  ask for a code at all - these three bound actual SMS *send volume* (and therefore cost): per
     *  number, per IP, and a daily global cap across every number, each independently configurable
     *  (docs/development/sms-provider-setup.md). Checked after the resend limiter (cheaper, more
     *  specific checks first) but before the code is generated/saved, so a capped request never wastes
     *  a DB write or a code slot it won't get to use. */
    private void checkSendCaps(String phone, String ip) {
        int maxPerNumber = config.getInt("sms.send.max_per_number.daily", 20);
        if (!rateLimiter.tryConsume("sms_send:number:" + phone, maxPerNumber, Duration.ofDays(1))) {
            throw new RateLimitExceededException();
        }
        if (ip != null && !ip.isBlank()) {
            int maxPerIp = config.getInt("sms.send.max_per_ip.daily", 50);
            if (!rateLimiter.tryConsume("sms_send:ip:" + ip, maxPerIp, Duration.ofDays(1))) {
                throw new RateLimitExceededException();
            }
        }
        int maxGlobal = config.getInt("sms.send.max_global.daily", 5000);
        if (!rateLimiter.tryConsume("sms_send:global", maxGlobal, Duration.ofDays(1))) {
            // No external alerting system exists yet (docs/development/sms-provider-setup.md) - this
            // ERROR-level, grep-able line is the interim signal ops needs to notice a real outage/abuse
            // scenario, same convention as every other operational log line in this codebase.
            log.error("operation=sms_global_daily_cap_exceeded result=blocked alert=true max={}", maxGlobal);
            throw new RateLimitExceededException();
        }
    }

    /** Throws {@link OtpVerificationException} on any failure; never reveals which case applied.
     *  @return the email the code was delivered to (email channel), or null for an SMS code. */
    @Transactional
    public String verify(String phone, String code, OtpPurpose purpose) {
        OtpRequest otp = otpRequests
                .findFirstByPhoneAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(phone, purpose)
                .orElseThrow(OtpVerificationException::new);

        int maxAttempts = config.getInt("otp.max.attempts", 5);
        Instant now = Instant.now();
        int incremented = otpRequests.incrementAttemptIfEligible(otp.getId(), now, maxAttempts);
        if (incremented == 0) {
            throw new OtpVerificationException();
        }
        if (!otpHasher.matches(code, otp.getCodeHash())) {
            throw new OtpVerificationException();
        }
        int consumed = otpRequests.consumeIfUnconsumed(otp.getId(), now);
        if (consumed == 0) {
            // Lost the race to a concurrent verify call that already consumed this exact code.
            throw new OtpVerificationException();
        }
        return otp.getEmail();
    }

    private String generateCode() {
        int bound = (int) Math.pow(10, CODE_DIGITS);
        int value = random.nextInt(bound);
        return String.format("%0" + CODE_DIGITS + "d", value);
    }
}
