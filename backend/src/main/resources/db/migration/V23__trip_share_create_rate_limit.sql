-- V23: security review finding, LOW, on trip sharing (docs/api/safety.md section 4) - creation had
-- no rate limit of its own; the active-link cap bounds how many can be simultaneously active, but not
-- how often a rider can loop create-then-revoke, which could generate unbounded trip_shares rows.

INSERT INTO configuration (key, value, value_type, scope, description) VALUES
    ('trip_share.create.rate.max.attempts', '10', 'INTEGER', 'GLOBAL',
        'Max trip-share creations per rider per window, independent of the simultaneous-active cap'),
    ('trip_share.create.rate.window.minutes', '10', 'INTEGER', 'GLOBAL',
        'Window for the above');
