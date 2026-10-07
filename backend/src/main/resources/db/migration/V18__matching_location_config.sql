-- V18: Phase 6 - driver availability, location updates, matching/offers, WebSocket. The core matching
-- parameters (driver.search.radius.m, driver.response.timeout.s, ride.max.search.time.s,
-- matching.max.offers, tracking.sample.interval.s) were already seeded in V1 and are used unchanged.
-- These are the new config keys this phase needs.

INSERT INTO configuration (key, value, value_type, scope, description) VALUES
    ('driver.location.rate.max.attempts', '1', 'INTEGER', 'GLOBAL',
        'Max accepted driver location updates per rate-limit window'),
    ('driver.location.rate.window.seconds', '2', 'INTEGER', 'GLOBAL',
        'Rate-limit window for driver location updates'),
    ('driver.location.max_age.seconds', '30', 'INTEGER', 'GLOBAL',
        'Reject a location update whose recordedAt is older than this'),
    ('driver.location.max_future_skew.seconds', '5', 'INTEGER', 'GLOBAL',
        'Reject a location update whose recordedAt is this far in the future (clock skew tolerance)'),
    ('driver.location.max_speed.kmh', '150', 'INTEGER', 'GLOBAL',
        'Reject a location update implying a speed above this since the driver''s last known point'),
    ('booking.pickup.approximate.radius.m', '300', 'INTEGER', 'GLOBAL',
        'Grid size used to round the pickup point shown to an offered (not-yet-accepted) driver'),
    ('websocket.max_connections_per_user', '3', 'INTEGER', 'GLOBAL',
        'Maximum concurrent WebSocket sessions per user');
