-- V2: users and authentication support tables.

CREATE TABLE users (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    phone             varchar(16)  NOT NULL CHECK (phone ~ '^\+[1-9][0-9]{7,14}$'), -- E.164
    phone_verified_at timestamptz,
    email             varchar(254),
    name              varchar(120),
    password_hash     varchar(255), -- admin roles only; customers/drivers authenticate by OTP
    role              varchar(20)  NOT NULL
                      CHECK (role IN ('CUSTOMER', 'DRIVER', 'ADMIN', 'SUPER_ADMIN', 'SUPPORT')),
    status            varchar(20)  NOT NULL DEFAULT 'ACTIVE'
                      CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED')),
    profile_photo_key varchar(500),
    mfa_secret_enc    text,         -- encrypted at application level; never stored in clear
    mfa_enabled       boolean      NOT NULL DEFAULT false,
    last_login_at     timestamptz,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_phone UNIQUE (phone),
    CONSTRAINT ck_users_password_admin_only CHECK (
        password_hash IS NULL OR role IN ('ADMIN', 'SUPER_ADMIN', 'SUPPORT')),
    CONSTRAINT ck_users_mfa_secret CHECK (mfa_secret_enc IS NULL OR role IN ('ADMIN', 'SUPER_ADMIN', 'SUPPORT'))
);
CREATE UNIQUE INDEX uq_users_email ON users (lower(email)) WHERE email IS NOT NULL;
CREATE INDEX idx_users_role_status ON users (role, status);
CREATE TRIGGER trg_users_updated_at BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE configuration
    ADD CONSTRAINT fk_configuration_updated_by FOREIGN KEY (updated_by) REFERENCES users (id);

-- Only the hash of a refresh token is stored. replaced_by supports rotation and reuse detection.
CREATE TABLE refresh_tokens (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid         NOT NULL REFERENCES users (id),
    token_hash  varchar(64)  NOT NULL,
    device_id   varchar(100),
    expires_at  timestamptz  NOT NULL,
    revoked_at  timestamptz,
    replaced_by uuid REFERENCES refresh_tokens (id),
    created_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash)
);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_expires ON refresh_tokens (expires_at);

-- Only a hash of the OTP is stored. Retention: 24 hours (purged by a job).
CREATE TABLE otp_requests (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    phone       varchar(16)  NOT NULL CHECK (phone ~ '^\+[1-9][0-9]{7,14}$'),
    code_hash   varchar(255) NOT NULL,
    purpose     varchar(20)  NOT NULL CHECK (purpose IN ('LOGIN', 'PHONE_VERIFY', 'ADMIN_MFA')),
    attempts    smallint     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    expires_at  timestamptz  NOT NULL,
    consumed_at timestamptz,
    created_at  timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_otp_requests_phone_created ON otp_requests (phone, created_at DESC);
CREATE INDEX idx_otp_requests_expires ON otp_requests (expires_at);
