-- V32: freshness bound for matching (docs/api/driver-earnings-ratings.md section 6, B6). Matching ignores
-- ONLINE drivers whose last server-received location (drivers.current_location_at) is older than this,
-- so a ghost-online driver whose app died is never offered a ride. No new column: current_location_at
-- already holds the server receive time and is written on every location update. Additive; rollback =
-- delete the row (the code falls back to the same default of 120).

INSERT INTO configuration (key, value, value_type, scope, description) VALUES
    ('matching.driver.location.max.age.seconds', '120', 'INTEGER', 'GLOBAL',
        'Matching only offers rides to ONLINE drivers whose last location update is at most this many seconds old (10-3600)');
