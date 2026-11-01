-- V37: progressive admin login delay (docs/security/admin-stepup-lockout-recovery-design.md section 3).
-- One row per email that has recently failed to sign in; a success or an unlock deletes it. The switch ships OFF so the
-- old fixed-window limit keeps applying until it is flipped on staging, then production. Additive; rollback = drop the
-- table and delete the four configuration rows.

CREATE TABLE admin_login_throttle (
    email           text PRIMARY KEY,
    failure_count   integer      NOT NULL,
    window_start    timestamptz  NOT NULL,
    window_failures integer      NOT NULL,
    last_failure_at timestamptz  NOT NULL,
    locked_until    timestamptz  NOT NULL
);
CREATE INDEX idx_admin_login_throttle_last_failure ON admin_login_throttle (last_failure_at);

INSERT INTO configuration (key, value, value_type, scope, description) VALUES
    ('admin.login.progressive.enabled',    'false', 'BOOLEAN', 'GLOBAL', 'Replace the hard login lockout with a wait that doubles per failure (1s, 2s, 4s ... capped)'),
    ('admin.login.max.delay.seconds',      '900',   'INTEGER', 'GLOBAL', 'Longest wait after repeated failed admin logins'),
    ('admin.login.alert.failures',         '10',    'INTEGER', 'GLOBAL', 'Failed logins for one email inside the alert window that write an admin_login_failures_high log line'),
    ('admin.login.alert.window.minutes',   '10',    'INTEGER', 'GLOBAL', 'Window for the failed-login alert');
