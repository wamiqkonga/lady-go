-- V40: admin live map freshness bound. The live map (GET /api/v1/admin/live-map) shows each driver's last
-- known position from drivers.current_location / current_location_at (no history); positions older than
-- this many minutes are dropped so a dead app never shows a ghost marker. Additive; rollback = delete the
-- row (the code falls back to the same default of 10).

INSERT INTO configuration (key, value, value_type, scope, description) VALUES
    ('admin.livemap.max_age.minutes', '10', 'INTEGER', 'GLOBAL',
        'Admin live map drops driver positions older than this many minutes (1-120)');
