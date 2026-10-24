package com.ladygo.auth.challenge;

/**
 * Bot-protection hook for the public OTP-request endpoint, called for explicitly (not an invented
 * requirement): a pluggable, config-driven step ahead of the existing DB-backed OTP/SMS rate limits
 * (docs/product/driver-registration-web.md section 2.3), not a replacement for them.
 *
 * <p>Deliberately no vendor chosen here - same "config-driven, no provider-specific code" pattern as
 * {@code com.ladygo.notifications.sms.SmsSender}. {@link NoOpChallengeVerifier} is the only
 * implementation shipped: it checks that a token is present, nothing more, and is explicitly a
 * placeholder for a real provider (hCaptcha, Cloudflare Turnstile, reCAPTCHA, ...) - whoever adds one
 * implements this interface and wires it in, no change needed anywhere this is called from.
 */
public interface ChallengeVerifier {

    boolean verify(String challengeToken);
}
