-- V34: the map pin-drop reverse-geocode has its own per-user rate limit, separate from the fare-estimate
-- bucket (bookings.maps.*), so panning the map cannot use up the calls a fare estimate needs. A rider pans
-- and settles more often than they request a fare, hence the higher default.

INSERT INTO configuration (key, value, value_type, scope, description) VALUES
    ('maps.pin.rate.max.attempts',     '30', 'INTEGER', 'GLOBAL', 'Max map-pin reverse-geocode calls (each may call the Maps provider) per user per window'),
    ('maps.pin.rate.window.minutes',   '1',  'INTEGER', 'GLOBAL', 'Rolling window for the above limit');
