-- DEV ONLY reference data (loaded by the dev profile; see application-dev.yml).
-- The service area is a rough placeholder box around Srinagar, NOT a real operating boundary.
-- Real service areas are created by admins (Phase 9) or by a reviewed production migration.
INSERT INTO cities (name, country, timezone, currency, service_area, active)
VALUES ('Srinagar', 'IN', 'Asia/Kolkata', 'INR',
        ST_GeogFromText('POLYGON((74.70 33.95, 74.95 33.95, 74.95 34.20, 74.70 34.20, 74.70 33.95))'),
        true)
ON CONFLICT (name, country) DO NOTHING;
