-- V15: per-customer rate limit on Maps-provider-calling endpoints (adversarial review finding, HIGH:
-- /bookings/estimate and /bookings had no bound on a single customer's call volume, defeating
-- ADR-011's cost controls - docs/api/maps-service.md section 4/5).

INSERT INTO configuration (key, value, value_type, scope, description) VALUES
    ('bookings.maps.rate.max.attempts',     '20', 'INTEGER', 'GLOBAL', 'Max fare-estimate/booking-create calls (each may call the Maps provider) per customer per window'),
    ('bookings.maps.rate.window.minutes',   '1',  'INTEGER', 'GLOBAL', 'Rolling window for the above limit');
