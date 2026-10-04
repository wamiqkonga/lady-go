-- V12: Phase 3 auth hardening - database-backed rate limiting, admin enrolment state, TOTP replay guard.
-- See docs/security/auth-design.md.

CREATE TABLE auth_rate_limits (
    bucket_key    varchar(200) PRIMARY KEY,
    window_start  timestamptz  NOT NULL,
    attempt_count integer      NOT NULL DEFAULT 1,
    updated_at    timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_auth_rate_limits_window ON auth_rate_limits (window_start);

ALTER TABLE users
    ADD COLUMN must_change_password    boolean NOT NULL DEFAULT false,
    ADD COLUMN mfa_pending_secret_enc  text,
    ADD COLUMN mfa_last_used_step      bigint;

ALTER TABLE users ADD CONSTRAINT ck_users_mfa_pending_secret CHECK (
    mfa_pending_secret_enc IS NULL OR role IN ('ADMIN', 'SUPER_ADMIN', 'SUPPORT'));
ALTER TABLE users ADD CONSTRAINT ck_users_must_change_password CHECK (
    must_change_password = false OR role IN ('ADMIN', 'SUPER_ADMIN', 'SUPPORT'));

-- Phase 3 configurable auth rules (docs/security/auth-design.md section 12, OQ-3). Units are part of the key name.
INSERT INTO configuration (key, value, value_type, scope, description) VALUES
    ('otp.expiry.seconds',          '300', 'INTEGER', 'GLOBAL', 'How long an OTP code stays valid after it is sent'),
    ('otp.max.attempts',            '5',   'INTEGER', 'GLOBAL', 'Max verify attempts against a single OTP code'),
    ('otp.resend.cooldown.seconds', '30',  'INTEGER', 'GLOBAL', 'Minimum gap between two OTP requests for the same phone+purpose'),
    ('otp.resend.max.per.hour',     '5',   'INTEGER', 'GLOBAL', 'Max OTP requests per phone+purpose per rolling hour'),
    ('refresh.token.ttl.days',      '30',  'INTEGER', 'GLOBAL', 'Absolute lifetime of a refresh token, regardless of rotation'),
    ('access.token.ttl.minutes',           '15', 'INTEGER', 'GLOBAL', 'Access token lifetime for CUSTOMER/DRIVER'),
    ('admin.access.token.ttl.minutes',     '10', 'INTEGER', 'GLOBAL', 'Access token lifetime for ADMIN/SUPER_ADMIN/SUPPORT and enrolment tokens'),
    ('admin.login.max.attempts',           '5',  'INTEGER', 'GLOBAL', 'Max admin login attempts per window, per email or per IP'),
    ('admin.login.window.minutes',         '15', 'INTEGER', 'GLOBAL', 'Rolling window for admin login attempt limiting');
