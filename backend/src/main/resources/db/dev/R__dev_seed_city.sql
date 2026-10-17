-- DEV ONLY reference data (loaded by the dev profile; see application-dev.yml).
-- The service area is a rough placeholder box around Srinagar, NOT a real operating boundary.
-- Real service areas are created by admins (Phase 9) or by a reviewed production migration.
INSERT INTO cities (name, country, timezone, currency, service_area, active)
VALUES ('Srinagar', 'IN', 'Asia/Kolkata', 'INR',
        ST_GeogFromText('POLYGON((74.70 33.95, 74.95 33.95, 74.95 34.20, 74.70 34.20, 74.70 33.95))'),
        true)
ON CONFLICT (name, country) DO NOTHING;

-- DEV ONLY placeholder fare rule so a local rider can get an estimate (an estimate 404s for a city with
-- no active rule for the vehicle type). Amounts are minor units (paise) and are NOT real pricing:
-- 50.00 base, 12.00/km, 1.00/min, 80.00 minimum, 50.00 cancellation fee, 5% service fee.
-- Real pricing is set by a reviewed production migration or the admin tooling, never by this file.
INSERT INTO pricing_rules (city_id, vehicle_type, currency, base_fare, per_km, per_minute, minimum_fare,
                           cancellation_fee, service_fee_pct, service_fee_flat, effective_from)
SELECT c.id, 'CAB', 'INR', 5000, 1200, 100, 8000, 5000, 5.00, 0, now() - interval '1 day'
FROM cities c
WHERE c.name = 'Srinagar' AND c.country = 'IN'
  AND NOT EXISTS (SELECT 1 FROM pricing_rules p WHERE p.city_id = c.id AND p.vehicle_type = 'CAB');
