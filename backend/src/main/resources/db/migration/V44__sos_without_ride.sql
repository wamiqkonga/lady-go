-- V44: an SOS can be raised before a ride row exists (rides only exist from DRIVER_ACCEPTED) or with no
-- active booking at all (docs/api/safety.md section 1). ride_id becomes nullable; booking_id is added.

ALTER TABLE safety_incidents ALTER COLUMN ride_id DROP NOT NULL;
ALTER TABLE safety_incidents ADD COLUMN booking_id uuid REFERENCES bookings (id);
CREATE INDEX idx_safety_incidents_booking ON safety_incidents (booking_id);

INSERT INTO configuration (key, value, value_type, scope, description) VALUES
    ('sos.rate.max.attempts', '3', 'INTEGER', 'GLOBAL',
        'Max SOS incidents created per reporter per window (the latest attempt in a window is never blocked from reaching the user, only from creating more queue rows)'),
    ('sos.rate.window.minutes', '15', 'INTEGER', 'GLOBAL', 'Window for sos.rate.max.attempts');
