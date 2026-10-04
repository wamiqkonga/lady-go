-- V11: device tokens, notification log, audit log.

CREATE TABLE device_tokens (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    platform     varchar(10)  NOT NULL CHECK (platform IN ('ANDROID', 'IOS')),
    app          varchar(10)  NOT NULL CHECK (app IN ('CUSTOMER', 'DRIVER')),
    token        varchar(500) NOT NULL,
    last_seen_at timestamptz  NOT NULL DEFAULT now(),
    created_at   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_device_tokens_token UNIQUE (token)
);
CREATE INDEX idx_device_tokens_user ON device_tokens (user_id);

-- Delivery log only: message bodies are never stored (they can contain OTPs). Retention: 90 days.
CREATE TABLE notification_logs (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      uuid REFERENCES users (id) ON DELETE SET NULL,
    event_type   varchar(60) NOT NULL,
    channel      varchar(10) NOT NULL CHECK (channel IN ('PUSH', 'SMS', 'EMAIL', 'IN_APP')),
    status       varchar(15) NOT NULL CHECK (status IN ('QUEUED', 'SENT', 'DELIVERED', 'FAILED')),
    provider_ref varchar(150),
    error_code   varchar(100),
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_notification_logs_user ON notification_logs (user_id, created_at DESC);
CREATE INDEX idx_notification_logs_created ON notification_logs (created_at);

-- Who did what, to what, when. Append-only; retained for at least two years. No foreign keys on purpose:
-- audit rows must survive deletion of the things they describe.
CREATE TABLE audit_logs (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id    uuid,
    actor_role  varchar(20),
    action      varchar(100) NOT NULL,
    target_type varchar(50),
    target_id   varchar(64),
    metadata    jsonb,
    ip          varchar(45),  -- textual IPv4/IPv6
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_logs_actor ON audit_logs (actor_id, created_at);
CREATE INDEX idx_audit_logs_target ON audit_logs (target_type, target_id);
CREATE TRIGGER trg_audit_logs_append_only BEFORE UPDATE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION prevent_update();
