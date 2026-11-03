-- V43: customer registration and password reset.
-- (1) one-time codes for both, delivered by email; (2) riders may now hold a password (staff and drivers already could, V42). Additive: only widens two CHECKs, existing rows are untouched.
-- Rollback = drop and re-add the old CHECKs (after clearing customer password hashes).
ALTER TABLE otp_requests DROP CONSTRAINT otp_requests_purpose_check;
ALTER TABLE otp_requests ADD CONSTRAINT otp_requests_purpose_check
    CHECK (purpose IN ('LOGIN', 'PHONE_VERIFY', 'ADMIN_MFA', 'REGISTER', 'PASSWORD_RESET'));

ALTER TABLE users DROP CONSTRAINT ck_users_password_by_role;
ALTER TABLE users ADD CONSTRAINT ck_users_password_by_role
    CHECK (password_hash IS NULL OR role IN ('ADMIN', 'SUPER_ADMIN', 'SUPPORT', 'DRIVER', 'CUSTOMER'));
