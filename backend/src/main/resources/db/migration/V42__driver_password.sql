-- V42: driver password sign-in (docs/development/plans/2026-11-02-driver-registration-amendments.md).
-- Drivers reuse users.password_hash (same hash as admins) and the admin_password_resets / admin_login_throttle tables
-- (keyed by user_id / a "driver:" key). Schema changes: the time a password was last set, and the V2 check that let only
-- staff hold a password now also lets DRIVER hold one (CUSTOMER still cannot). Nothing is dropped from data.
-- Rollback: only possible while no driver has a password (UPDATE users SET password_hash = NULL WHERE role = 'DRIVER'),
-- then restore ck_users_password_admin_only, drop password_set_at and delete the configuration rows.

ALTER TABLE users ADD COLUMN password_set_at timestamptz;

ALTER TABLE users DROP CONSTRAINT ck_users_password_admin_only;
ALTER TABLE users ADD CONSTRAINT ck_users_password_by_role CHECK (
    password_hash IS NULL OR role IN ('ADMIN', 'SUPER_ADMIN', 'SUPPORT', 'DRIVER'));

INSERT INTO configuration (key, value, value_type, scope, description) VALUES
    ('auth.driver_password.enabled',            'true', 'BOOLEAN', 'GLOBAL', 'Kill switch for driver password sign-in, set-password and reset (OTP sign-in is unaffected)'),
    ('driver.password.set.fresh.minutes',       '10',   'INTEGER', 'GLOBAL', 'A driver with no password can set one only within this many minutes of a sign-in'),
    ('driver.password.login.max.per.ip',        '20',   'INTEGER', 'GLOBAL', 'Driver password sign-in attempts per IP per 15 minutes'),
    ('driver.password_reset.ttl.minutes',       '30',   'INTEGER', 'GLOBAL', 'How long an emailed driver password-reset link stays valid'),
    ('driver.password_reset.max.requests',      '3',    'INTEGER', 'GLOBAL', 'Max driver password-reset emails per account per window'),
    ('driver.password_reset.window.minutes',    '60',   'INTEGER', 'GLOBAL', 'Rolling window for driver password-reset request limiting');
