-- V5: versioned pricing rules. Rows are never edited in place: a change creates a new row and
-- closes the previous one (effective_to). Bookings reference the rule version used for the estimate.

CREATE TABLE pricing_rules (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    city_id          uuid        NOT NULL REFERENCES cities (id),
    vehicle_type     varchar(20) NOT NULL CHECK (vehicle_type IN ('CAB', 'SUV', 'AUTO', 'BIKE')),
    currency         char(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    base_fare        bigint      NOT NULL CHECK (base_fare >= 0),         -- minor units
    per_km           bigint      NOT NULL CHECK (per_km >= 0),
    per_minute       bigint      NOT NULL CHECK (per_minute >= 0),
    minimum_fare     bigint      NOT NULL CHECK (minimum_fare >= 0),
    cancellation_fee bigint      NOT NULL DEFAULT 0 CHECK (cancellation_fee >= 0),
    service_fee_pct  numeric(5, 2) NOT NULL DEFAULT 0 CHECK (service_fee_pct BETWEEN 0 AND 100),
    service_fee_flat bigint      NOT NULL DEFAULT 0 CHECK (service_fee_flat >= 0),
    surge_config     jsonb,
    effective_from   timestamptz NOT NULL,
    effective_to     timestamptz,
    created_by       uuid REFERENCES users (id),
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_pricing_rules_period CHECK (effective_to IS NULL OR effective_to > effective_from),
    -- At most one rule applies to a city + vehicle type at any moment.
    CONSTRAINT ex_pricing_rules_no_overlap EXCLUDE USING gist (
        city_id WITH =, vehicle_type WITH =, tstzrange(effective_from, effective_to) WITH &&)
);
CREATE INDEX idx_pricing_rules_lookup ON pricing_rules (city_id, vehicle_type, effective_from DESC);
