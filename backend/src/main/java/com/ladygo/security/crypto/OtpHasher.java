package com.ladygo.security.crypto;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * HMAC-SHA256 hashing for OTP codes (docs/security/auth-design.md section 4, OQ-1: chosen over BCrypt
 * because OTP verification happens at much higher volume than admin password checks and does not need
 * BCrypt's deliberate slowness - the secret key already gives it resistance to offline guessing).
 */
@Component
public class OtpHasher {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secretKey;

    public OtpHasher(@Value("${ladygo.otp.hash-secret}") String secret) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "ladygo.otp.hash-secret (LADYGO_OTP_HASH_SECRET) must be at least 32 bytes (256 bits).");
        }
        this.secretKey = keyBytes;
    }

    public String hash(String code) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secretKey, ALGORITHM));
            byte[] digest = mac.doFinal(code.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException("Unable to hash OTP code", ex);
        }
    }

    public boolean matches(String code, String hash) {
        return constantTimeEquals(hash(code), hash);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
