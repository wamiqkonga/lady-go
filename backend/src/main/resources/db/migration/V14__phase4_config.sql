-- V14: Phase 4 configuration defaults (docs/api/customer-profile.md, docs/api/booking.md).

INSERT INTO configuration (key, value, value_type, scope, description) VALUES
    ('customer.saved_locations.max.count',   '10', 'INTEGER', 'GLOBAL', 'Max saved locations per customer'),
    ('customer.emergency_contacts.max.count', '5',  'INTEGER', 'GLOBAL', 'Max emergency contacts per customer'),
    ('booking.fare.change.tolerance.pct',     '5',  'DECIMAL', 'GLOBAL', 'Allowed drift between an estimate and the fare at booking creation before FARE_CHANGED is returned'),
    ('ride.pin.max.attempts',                 '5',  'INTEGER', 'GLOBAL', 'Max ride PIN verification attempts before lockout, per ride'),
    ('ride.pin.lockout.window.minutes',       '15', 'INTEGER', 'GLOBAL', 'Rolling window for ride PIN attempt limiting, per ride'),
    ('maps.mock.average.speed.kmh',           '25', 'INTEGER', 'GLOBAL', 'Assumed average speed used to turn straight-line distance into a duration when the real route cannot be computed (mock provider and estimate degrade fallback)');
