-- V29: config-driven bot-challenge hook on the public OTP-request endpoint
-- (docs/product/driver-registration-web.md section 2.3). Default off everywhere - ChallengeVerifier
-- ships only a placeholder implementation (no vendor chosen), so there is nothing real to turn on yet.

INSERT INTO configuration (key, value, value_type, scope, description) VALUES
    ('otp.challenge.enabled', 'false', 'BOOLEAN', 'GLOBAL',
        'When true, POST /auth/otp/request requires a valid challengeToken (com.ladygo.auth.challenge.ChallengeVerifier) before anything else, including rate limits');
