-- V20: ride lifecycle (docs/api/ride-lifecycle.md). Arrive/PIN-start/complete/early-end, the
-- proximity+freshness arrived/start gate, ride PIN durable lockout, cash confirmation + dispute
-- window, and ride_flags as the one durable record behind every "admin should look at this" signal
-- this phase introduces.

ALTER TABLE drivers ADD COLUMN current_location_accuracy_m real;

ALTER TABLE bookings ADD COLUMN ride_pin_locked_at timestamptz;
ALTER TABLE bookings ADD COLUMN cash_confirmed_at timestamptz;
ALTER TABLE bookings ADD COLUMN driver_arriving_at timestamptz;

CREATE TABLE ride_flags (
    id                       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Nullable: a RIDER_SAFETY_CANCEL can happen before RIDE_BOOTSTRAP's trigger (DRIVER_ACCEPTED) has
    -- created a rides row at all (e.g. a rider cancelling for a safety reason right after assignment,
    -- before the driver has even accepted). Every other flag type always has a ride by the time it's raised.
    ride_id                  uuid REFERENCES rides (id),
    booking_id               uuid        NOT NULL REFERENCES bookings (id),
    customer_id              uuid        NOT NULL REFERENCES customers (id),
    driver_id                uuid        NOT NULL REFERENCES drivers (id),
    type                     varchar(40) NOT NULL CHECK (type IN
        ('EARLY_END', 'COMPLETION_FAR_FROM_DESTINATION', 'PIN_LOCKOUT', 'CASH_DISPUTE',
         'RIDER_SAFETY_CANCEL', 'RIDER_CONFIRMED_ARRIVAL')),
    raised_by                varchar(10) NOT NULL CHECK (raised_by IN ('CUSTOMER', 'DRIVER', 'ADMIN', 'SYSTEM')),
    distance_from_expected_m integer,
    cap_exceeded             boolean     NOT NULL DEFAULT false,
    rider_response           varchar(15) CHECK (rider_response IN ('ACKNOWLEDGED', 'DISPUTED')),
    rider_responded_at       timestamptz,
    created_at               timestamptz NOT NULL DEFAULT now(),
    updated_at               timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_ride_flags_booking ON ride_flags (booking_id);
CREATE INDEX idx_ride_flags_customer_type_created ON ride_flags (customer_id, type, created_at);
CREATE INDEX idx_ride_flags_driver_type_created ON ride_flags (driver_id, type, created_at);
CREATE TRIGGER trg_ride_flags_updated_at BEFORE UPDATE ON ride_flags
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

INSERT INTO configuration (key, value, value_type, scope, description) VALUES
    ('ride.gate.pickup_radius.m', '200', 'INTEGER', 'GLOBAL',
        'Max distance from pickup to allow the arrived/start gate to pass'),
    ('ride.gate.max_accuracy_credit.m', '100', 'INTEGER', 'GLOBAL',
        'Cap on how much reported GPS accuracy widens the gate radius'),
    ('ride.gate.max_location_age.seconds', '30', 'INTEGER', 'GLOBAL',
        'Freshness bound (server receive time) for the location used at the arrived/start/completion-check gate'),
    ('ride.gate.rider_confirm_wait.seconds', '300', 'INTEGER', 'GLOBAL',
        'How long DRIVER_ARRIVING must have been active before the rider may bypass a stuck arrived gate'),
    ('ride.pin.driver.max.attempts', '15', 'INTEGER', 'GLOBAL',
        'Max ride PIN verification attempts per driver across all rides, in the window below'),
    ('ride.pin.driver.window.minutes', '60', 'INTEGER', 'GLOBAL',
        'Rolling window for the per-driver ride PIN attempt limit'),
    ('ride.cash.dispute.window.minutes', '60', 'INTEGER', 'GLOBAL',
        'Rider window to dispute a cash-collected confirmation before the booking auto-completes'),
    ('rider.safety_cancel.weekly_cap.max', '3', 'INTEGER', 'GLOBAL',
        'Penalty-free rider safety-reason cancels allowed per rider per rolling 7 days, across all drivers'),
    ('rider.safety_cancel.pair_cap.max', '1', 'INTEGER', 'GLOBAL',
        'Penalty-free rider safety-reason cancels allowed against the same driver per rolling 7 days');
