package com.ladygo.bookings.service;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.ladygo.config.ConfigurationService;
import com.ladygo.security.ratelimit.RateLimitExceededException;
import com.ladygo.security.ratelimit.RateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Ride PIN, derived not stored (docs/api/booking.md section 3, B4). {@code pin = HMAC-SHA256(secret,
 * bookingId) mod 10000}, zero-padded to 4 digits - deterministic, so it can be recomputed on every
 * request that needs it; nothing is cached or persisted. {@code bookings.ride_pin_hash} (Phase 2) is
 * deliberately left unused by this design.
 */
@Service
public class RidePinService {

    private static final int PIN_DIGITS = 4;
    private static final int PIN_MODULUS = (int) Math.pow(10, PIN_DIGITS);

    private final byte[] secretKey;
    private final RateLimiter rateLimiter;
    private final ConfigurationService config;

    public RidePinService(
            @Value("${ladygo.ride.pin-secret}") String secret, RateLimiter rateLimiter, ConfigurationService config) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "ladygo.ride.pin-secret (LADYGO_RIDE_PIN_SECRET) must be at least 32 bytes (256 bits).");
        }
        this.secretKey = keyBytes;
        this.rateLimiter = rateLimiter;
        this.config = config;
    }

    public String derive(UUID bookingId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey, "HmacSHA256"));
            byte[] digest = mac.doFinal(bookingId.toString().getBytes(StandardCharsets.UTF_8));
            int offset = digest.length - 4;
            int value = ((digest[offset] & 0x7F) << 24)
                    | ((digest[offset + 1] & 0xFF) << 16)
                    | ((digest[offset + 2] & 0xFF) << 8)
                    | (digest[offset + 3] & 0xFF);
            int pin = value % PIN_MODULUS;
            return String.format("%0" + PIN_DIGITS + "d", pin);
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException("Unable to derive ride PIN", ex);
        }
    }

    /**
     * Rate-limited and lockable per ride (not globally) via Phase 3's {@link RateLimiter} - one
     * bucket per {@code bookingId}, so brute-forcing one ride's 10,000 possible PINs is bounded the
     * same way a login brute-force is, without touching any other ride's budget.
     */
    public boolean verify(UUID bookingId, String candidatePin) {
        int maxAttempts = config.getInt("ride.pin.max.attempts", 5);
        Duration window = config.getMinutes("ride.pin.lockout.window.minutes", Duration.ofMinutes(15));
        if (!rateLimiter.tryConsume("ride_pin:" + bookingId, maxAttempts, window)) {
            throw new RateLimitExceededException();
        }
        return derive(bookingId).equals(candidatePin);
    }
}
