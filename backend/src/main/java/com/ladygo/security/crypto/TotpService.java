package com.ladygo.security.crypto;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * RFC 6238 TOTP (HMAC-SHA1, 30s step, 6 digits). Replay rejection is the caller's responsibility
 * (docs/security/auth-design.md section 3.4): this class only says whether a code is valid for some step
 * within the window and returns that step so the caller can reject re-use of an already-accepted step.
 */
@Component
public class TotpService {

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int STEP_SECONDS = 30;
    private static final int DIGITS = 6;
    private static final int WINDOW = 1;
    private static final String ISSUER = "LadyGo";

    public String generateSecret() {
        byte[] bytes = new byte[20];
        new SecureRandom().nextBytes(bytes);
        return base32Encode(bytes);
    }

    public String provisioningUri(String accountEmail, String base32Secret) {
        String label = URLEncoder.encode(ISSUER + ":" + accountEmail, StandardCharsets.UTF_8);
        return "otpauth://totp/" + label + "?secret=" + base32Secret + "&issuer=" + ISSUER
                + "&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    public long currentStep(Instant now) {
        return now.getEpochSecond() / STEP_SECONDS;
    }

    /** Returns the matched step if {@code code} is valid for any step in the +/- window, else empty. */
    public OptionalLong verify(String base32Secret, String code, Instant now) {
        long reference = currentStep(now);
        for (long step = reference - WINDOW; step <= reference + WINDOW; step++) {
            if (generateCode(base32Secret, step).equals(code)) {
                return OptionalLong.of(step);
            }
        }
        return OptionalLong.empty();
    }

    public String generateCode(String base32Secret, long step) {
        byte[] key = base32Decode(base32Secret);
        byte[] data = new byte[8];
        for (int i = 7; i >= 0; i--) {
            data[i] = (byte) (step & 0xFF);
            step >>= 8;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(data);
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            int otp = binary % (int) Math.pow(10, DIGITS);
            return String.format("%0" + DIGITS + "d", otp);
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException("Unable to generate TOTP code", ex);
        }
    }

    /** Minimal RFC 4648 base32 (no padding), sufficient for a random 160-bit TOTP secret. */
    private static String base32Encode(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                out.append(BASE32_ALPHABET.charAt((buffer >> (bitsLeft - 5)) & 0x1F));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            out.append(BASE32_ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1F));
        }
        return out.toString();
    }

    private static byte[] base32Decode(String encoded) {
        String clean = encoded.toUpperCase(java.util.Locale.ROOT).replace("=", "");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bitsLeft = 0;
        for (char c : clean.toCharArray()) {
            int value = BASE32_ALPHABET.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("Invalid base32 character: " + c);
            }
            buffer = (buffer << 5) | value;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out.write((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        return out.toByteArray();
    }
}
