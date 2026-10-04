-- V7: rides and location history.

CREATE TABLE rides (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id        uuid        NOT NULL REFERENCES bookings (id),
    driver_id         uuid        NOT NULL REFERENCES drivers (id),
    customer_id       uuid        NOT NULL REFERENCES customers (id),
    status            varchar(20) NOT NULL DEFAULT 'ASSIGNED'
        CHECK (status IN ('ASSIGNED', 'STARTED', 'COMPLETED', 'CANCELLED')),
    started_at        timestamptz,
    completed_at      timestamptz,
    start_location    geography(Point, 4326),
    end_location      geography(Point, 4326),
    actual_distance_m integer CHECK (actual_distance_m >= 0),
    actual_duration_s integer CHECK (actual_duration_s >= 0),
    version           bigint      NOT NULL DEFAULT 0,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_rides_booking UNIQUE (booking_id),
    CONSTRAINT ck_rides_times CHECK (completed_at IS NULL OR started_at IS NULL OR completed_at >= started_at)
);
CREATE INDEX idx_rides_driver ON rides (driver_id, created_at DESC);
CREATE INDEX idx_rides_customer ON rides (customer_id, created_at DESC);
CREATE TRIGGER trg_rides_updated_at BEFORE UPDATE ON rides
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Location history is written ONLY during active rides at a sampled interval (privacy by design), and is
-- partitioned by month so retention (90 days) is a cheap DROP of old partitions.
-- High-volume writes use JDBC batch inserts (Phase 7), so there is intentionally no JPA entity for it.
CREATE TABLE location_history (
    id          uuid        NOT NULL DEFAULT gen_random_uuid(),
    ride_id     uuid        NOT NULL REFERENCES rides (id),
    driver_id   uuid        NOT NULL REFERENCES drivers (id),
    location    geography(Point, 4326) NOT NULL,
    accuracy_m  real CHECK (accuracy_m >= 0),
    heading     real CHECK (heading >= 0 AND heading < 360),
    speed_mps   real CHECK (speed_mps >= 0),
    recorded_at timestamptz NOT NULL,
    PRIMARY KEY (id, recorded_at)
) PARTITION BY RANGE (recorded_at);

CREATE INDEX idx_location_history_ride_time ON location_history (ride_id, recorded_at);
CREATE INDEX idx_location_history_location ON location_history USING gist (location);

-- Catch-all so an insert never fails because a monthly partition has not been created yet.
-- Operations must create next month's partition ahead of time (function below, scheduled in Phase 12).
CREATE TABLE location_history_default PARTITION OF location_history DEFAULT;

CREATE FUNCTION create_location_history_partition(month_start date) RETURNS void AS $$
DECLARE
    from_ts timestamptz := date_trunc('month', month_start::timestamp) AT TIME ZONE 'UTC';
    to_ts   timestamptz := (date_trunc('month', month_start::timestamp) + interval '1 month') AT TIME ZONE 'UTC';
    part    text := format('location_history_%s', to_char(month_start, 'YYYY_MM'));
BEGIN
    EXECUTE format('CREATE TABLE IF NOT EXISTS %I PARTITION OF location_history FOR VALUES FROM (%L) TO (%L)',
                   part, from_ts, to_ts);
END;
$$ LANGUAGE plpgsql;

-- Previous month through the next three.
SELECT create_location_history_partition((date_trunc('month', now()) + (m || ' month')::interval)::date)
FROM generate_series(-1, 3) AS m;
