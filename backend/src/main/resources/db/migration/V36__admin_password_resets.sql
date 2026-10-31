-- V36: admin password recovery (docs/security/admin-stepup-lockout-recovery-design.md section 4).
-- Only the SHA-256 of the emailed token is stored, never the token itself. A row is single-use (used_at) and short-lived
-- (expires_at). Additive; rollback = drop the table and delete the three configuration rows.

CREATE TABLE admin_password_resets (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid         NOT NULL REFERENCES users (id),
    token_hash varchar(64) NOT NULL UNIQUE,
    expires_at timestamptz  NOT NULL,
    used_at    timestamptz,
    created_at timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_admin_password_resets_user ON admin_password_resets (user_id) WHERE used_at IS NULL;

INSERT INTO configuration (key, value, value_type, scope, description) VALUES
    ('admin.password_reset.ttl.minutes',   '30', 'INTEGER', 'GLOBAL', 'How long an emailed admin password-reset link stays valid'),
    ('admin.password_reset.max.requests',  '3',  'INTEGER', 'GLOBAL', 'Max password-reset emails per admin address per window'),
    ('admin.password_reset.window.minutes','60', 'INTEGER', 'GLOBAL', 'Rolling window for admin password-reset request limiting');
