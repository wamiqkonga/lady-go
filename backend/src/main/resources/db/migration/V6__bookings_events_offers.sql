-- V6: bookings, booking events (append-only), ride offers.
-- Status values mirror docs/product/user-flows.md section 1 (booking state machine).

CREATE TABLE bookings (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id           uuid        NOT NULL REFERENCES customers (id),
    driver_id             uuid REFERENCES drivers (id),
    city_id               uuid        NOT NULL REFERENCES cities (id),
    vehicle_type          varchar(20) NOT NULL CHECK (vehicle_type IN ('CAB', 'SUV', 'AUTO', 'BIKE')),
    pickup_location       geography(Point, 4326) NOT NULL,
    pickup_address        text        NOT NULL,
    destination_location  geography(Point, 4326) NOT NULL,
    destination_address   text        NOT NULL,
    estimated_distance_m  integer CHECK (estimated_distance_m >= 0),
    estimated_duration_s  integer CHECK (estimated_duration_s >= 0),
    estimated_fare        bigint CHECK (estimated_fare >= 0),   -- minor units
    final_fare            bigint CHECK (final_fare >= 0),
    currency              char(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    pricing_rule_id       uuid REFERENCES pricing_rules (id),
    payment_method        varchar(10) NOT NULL CHECK (payment_method IN ('CASH', 'ONLINE')),
    status                varchar(30) NOT NULL DEFAULT 'REQUESTED' CHECK (status IN (
        'REQUESTED', 'SEARCHING_DRIVER', 'DRIVER_ASSIGNED', 'DRIVER_ACCEPTED', 'DRIVER_ARRIVING',
        'DRIVER_ARRIVED', 'RIDE_STARTED', 'RIDE_COMPLETED', 'PAYMENT_PENDING', 'COMPLETED', 'CANCELLED')),
    cancel_reason         text,
    cancelled_by          varchar(20) CHECK (cancelled_by IN ('CUSTOMER', 'DRIVER', 'ADMIN', 'SYSTEM')),
    scheduled_at          timestamptz,
    ride_pin_hash         varchar(255),
    version               bigint      NOT NULL DEFAULT 0,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_bookings_cancelled CHECK ((status = 'CANCELLED') = (cancelled_by IS NOT NULL))
);
CREATE INDEX idx_bookings_pickup ON bookings USING gist (pickup_location);
CREATE INDEX idx_bookings_destination ON bookings USING gist (destination_location);
CREATE INDEX idx_bookings_customer_created ON bookings (customer_id, created_at DESC);
CREATE INDEX idx_bookings_driver_status ON bookings (driver_id, status);
CREATE INDEX idx_bookings_status_created ON bookings (status, created_at);
-- Concurrency guard: a driver can hold at most one committed booking at a time, enforced by the database
-- even if two matching workers race (verified by a test here, by concurrency tests in Phase 6).
CREATE UNIQUE INDEX uq_bookings_one_active_per_driver ON bookings (driver_id)
    WHERE driver_id IS NOT NULL
      AND status IN ('DRIVER_ACCEPTED', 'DRIVER_ARRIVING', 'DRIVER_ARRIVED', 'RIDE_STARTED');
CREATE TRIGGER trg_bookings_updated_at BEFORE UPDATE ON bookings
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE booking_events (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id  uuid        NOT NULL REFERENCES bookings (id),
    from_status varchar(30),
    to_status   varchar(30) NOT NULL,
    actor_type  varchar(20) NOT NULL CHECK (actor_type IN ('CUSTOMER', 'DRIVER', 'ADMIN', 'SYSTEM')),
    actor_id    uuid,
    reason      text,
    metadata    jsonb,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_booking_events_booking ON booking_events (booking_id, created_at);
CREATE TRIGGER trg_booking_events_append_only BEFORE UPDATE ON booking_events
    FOR EACH ROW EXECUTE FUNCTION prevent_update();

CREATE TABLE ride_offers (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id   uuid        NOT NULL REFERENCES bookings (id),
    driver_id    uuid        NOT NULL REFERENCES drivers (id),
    status       varchar(20) NOT NULL DEFAULT 'OFFERED'
        CHECK (status IN ('OFFERED', 'ACCEPTED', 'REJECTED', 'EXPIRED', 'CANCELLED')),
    distance_m   integer CHECK (distance_m >= 0),
    offered_at   timestamptz NOT NULL DEFAULT now(),
    expires_at   timestamptz,
    responded_at timestamptz,
    CONSTRAINT uq_ride_offers_booking_driver UNIQUE (booking_id, driver_id)
);
-- A booking can be accepted by exactly one driver.
CREATE UNIQUE INDEX uq_ride_offers_one_accepted ON ride_offers (booking_id) WHERE status = 'ACCEPTED';
CREATE INDEX idx_ride_offers_driver_status ON ride_offers (driver_id, status);
