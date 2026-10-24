package com.ladygo.storage;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-signs a {@code (key, expiry)} pair for {@link LocalFileStorage}'s dev/test stand-in for a real
 * cloud presigned URL (docs/api/driver-onboarding.md section 1) - shared by the storage implementation
 * (which signs) and the controller that serves the file (which verifies), so the two can never drift.
 */
final class LocalSignedUrlCodec {

    private final byte[] secretKey;

    LocalSignedUrlCodec(String secret) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "ladygo.storage.local.secret (LADYGO_LOCAL_STORAGE_SECRET) must be at least 32 bytes (256 bits).");
        }
        this.secretKey = keyBytes;
    }

    String sign(String key, long expiryEpochSeconds) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey, "HmacSHA256"));
            byte[] digest = mac.doFinal((key + "|" + expiryEpochSeconds).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException("Unable to sign local storage URL", ex);
        }
    }

    boolean isValid(String key, long expiryEpochSeconds, String candidateSignature) {
        if (System.currentTimeMillis() / 1000 > expiryEpochSeconds) {
            return false;
        }
        return constantTimeEquals(sign(key, expiryEpochSeconds), candidateSignature);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
