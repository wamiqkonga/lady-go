-- V1: extensions, shared helper functions, cities, configuration.
-- Conventions (docs/database/database-design.md): uuid keys, timestamptz UTC, money in minor units,
-- geography(…,4326) for locations, enumerations as varchar + CHECK.

CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS btree_gist; -- exclusion constraints (non-overlapping pricing rules)

-- Keeps updated_at correct even for writes that bypass the application (admin SQL, jobs).
CREATE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Append-only tables (events, audit): rows may be inserted and (by retention jobs) deleted, never edited.
CREATE FUNCTION prevent_update() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'table % is append-only; updates are not allowed', TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TABLE cities (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name         varchar(100) NOT NULL,
    country      char(2)      NOT NULL CHECK (country ~ '^[A-Z]{2}$'),
    timezone     varchar(64)  NOT NULL,
    currency     char(3)      NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    service_area geography(Polygon, 4326),
    active       boolean      NOT NULL DEFAULT true,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_cities_name_country UNIQUE (name, country)
);
CREATE INDEX idx_cities_service_area ON cities USING gist (service_area);
CREATE TRIGGER trg_cities_updated_at BEFORE UPDATE ON cities
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Runtime configuration. Business rules live here (or in pricing_rules), not in code.
-- updated_by gets its foreign key to users in V2.
CREATE TABLE configuration (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    key         varchar(150) NOT NULL,
    value       text         NOT NULL,
    value_type  varchar(10)  NOT NULL DEFAULT 'STRING'
                CHECK (value_type IN ('STRING', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'JSON')),
    scope       varchar(10)  NOT NULL CHECK (scope IN ('GLOBAL', 'CITY')),
    city_id     uuid REFERENCES cities (id),
    description text,
    updated_by  uuid,
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_configuration_scope_city CHECK (
        (scope = 'GLOBAL' AND city_id IS NULL) OR (scope = 'CITY' AND city_id IS NOT NULL))
);
CREATE UNIQUE INDEX uq_configuration_key_scope_city
    ON configuration (key, scope, COALESCE(city_id, '00000000-0000-0000-0000-000000000000'::uuid));
CREATE TRIGGER trg_configuration_updated_at BEFORE UPDATE ON configuration
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Safe defaults; admins change them at runtime (Phase 9). Units are part of the key name.
INSERT INTO configuration (key, value, value_type, scope, description) VALUES
    ('driver.search.radius.m',        '5000', 'INTEGER', 'GLOBAL', 'Initial radius when searching for nearby drivers'),
    ('driver.response.timeout.s',     '20',   'INTEGER', 'GLOBAL', 'Seconds a driver has to accept or reject an offer'),
    ('ride.max.search.time.s',        '180',  'INTEGER', 'GLOBAL', 'Give up searching for a driver after this many seconds'),
    ('matching.max.offers',           '10',   'INTEGER', 'GLOBAL', 'Maximum driver offers per booking'),
    ('ride.max.distance.m',           '100000','INTEGER','GLOBAL', 'Maximum allowed trip distance'),
    ('cancellation.grace.seconds',    '120',  'INTEGER', 'GLOBAL', 'Free cancellation window after a driver accepts'),
    ('tracking.sample.interval.s',    '10',   'INTEGER', 'GLOBAL', 'Location history sampling interval during rides'),
    ('ride.pin.enabled',              'true', 'BOOLEAN', 'GLOBAL', 'Require the customer ride PIN before a ride starts');
