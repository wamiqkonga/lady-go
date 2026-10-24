-- V24: admin-side triage state for ride_flags, separate from the rider's own riderResponse
-- (docs/product/admin-web-design.md section 8, open question 1 - decided: add it).

ALTER TABLE ride_flags ADD COLUMN admin_reviewed_by uuid REFERENCES users (id);
ALTER TABLE ride_flags ADD COLUMN admin_reviewed_at timestamptz;

CREATE INDEX idx_ride_flags_admin_reviewed_at ON ride_flags (admin_reviewed_at);
