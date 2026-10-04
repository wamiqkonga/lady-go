package com.ladygo.auth.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import com.ladygo.auth.domain.OtpPurpose;
import com.ladygo.auth.domain.OtpRequest;
import com.ladygo.auth.repository.OtpRequestRepository;
import com.ladygo.auth.sms.SmsService;
import com.ladygo.config.ConfigurationService;
import com.ladygo.security.crypto.OtpHasher;
import com.ladygo.security.ratelimit.RateLimitExceededException;
import com.ladygo.security.ratelimit.RateLimiter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OTP request/verify (docs/security/auth-design.md section 4). Request and verify are each a single
 * transaction built on atomic conditional updates in {@link OtpRequestRepository}, so concurrent calls
 * cannot double-count attempts or double-consume a code.
 */
@Service
public class OtpService {

    private static final int CODE_DIGITS = 6;

    private final OtpRequestRepository otpRequests;
    private final OtpHasher otpHasher;
    private final SmsService smsService;
    private final RateLimiter rateLimiter;
    private final ConfigurationService config;
    private final SecureRandom random = new SecureRandom();

    public OtpService(
            OtpRequestRepository otpRequests,
            OtpHasher otpHasher,
            SmsService smsService,
            RateLimiter rateLimiter,
            ConfigurationService config) {
        this.otpRequests = otpRequests;
        this.otpHasher = otpHasher;
        this.smsService = smsService;
        this.rateLimiter = rateLimiter;
        this.config = config;
    }

    @Transactional
    public void requestOtp(String phone, OtpPurpose purpose) {
        String bucket = "otp_request:" + phone + ":" + purpose;
        int maxPerHour = config.getInt("otp.resend.max.per.hour", 5);
        if (!rateLimiter.tryConsume(bucket, maxPerHour, Duration.ofHours(1))) {
            throw new RateLimitExceededException();
        }
        Duration cooldown = config.getSeconds("otp.resend.cooldown.seconds", Duration.ofSeconds(30));
        if (!rateLimiter.tryConsume(bucket + ":cooldown", 1, cooldown)) {
            throw new RateLimitExceededException();
        }

        Instant now = Instant.now();
        otpRequests.invalidateUnconsumed(phone, purpose.name(), now);

        String code = generateCode();
        Duration expiry = config.getSeconds("otp.expiry.seconds", Duration.ofMinutes(5));
        OtpRequest entity = new OtpRequest();
        entity.setPhone(phone);
        entity.setPurpose(purpose);
        entity.setCodeHash(otpHasher.hash(code));
        entity.setExpiresAt(now.plus(expiry));
        otpRequests.save(entity);

        smsService.sendOtp(phone, code, expiry);
    }

    /** Throws {@link OtpVerificationException} on any failure; never reveals which case applied. */
    @Transactional
    public void verify(String phone, String code, OtpPurpose purpose) {
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
    }

    private String generateCode() {
        int bound = (int) Math.pow(10, CODE_DIGITS);
        int value = random.nextInt(bound);
        return String.format("%0" + CODE_DIGITS + "d", value);
    }
}
