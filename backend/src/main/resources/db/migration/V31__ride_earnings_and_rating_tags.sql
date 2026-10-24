-- V31: driver earnings snapshot per completed ride and rating tags
-- (docs/api/driver-earnings-ratings.md sections 4, 5). Additive and forward-only: old app versions
-- ignore the new table and column, so an app rollback needs no schema rollback.

-- One row per ride, written exactly once when the ride completes (uq on ride_id makes replays harmless).
-- Display and payout records only - no money moves here. commission_bps is a snapshot of the rate in
-- force at completion (0 while earnings.commission.bps is unset), so changing the rate later never
-- rewrites history. payout_batch_id / settled_at are reserved for the manual payout report (step 8).
CREATE TABLE ride_earnings (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    ride_id         uuid        NOT NULL REFERENCES rides (id),
    driver_id       uuid        NOT NULL REFERENCES drivers (id),
    gross           bigint      NOT NULL,
    commission_bps  integer     NOT NULL DEFAULT 0,
    commission      bigint      NOT NULL,
    net             bigint      NOT NULL,
    currency        char(3)     NOT NULL,
    payment_method  varchar(10) NOT NULL CHECK (payment_method IN ('CASH', 'ONLINE')),
    earned_at       timestamptz NOT NULL,
    payout_batch_id uuid,
    settled_at      timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_ride_earnings_ride UNIQUE (ride_id),
    CONSTRAINT ck_ride_earnings_gross CHECK (gross >= 0),
    CONSTRAINT ck_ride_earnings_bps CHECK (commission_bps BETWEEN 0 AND 10000),
    CONSTRAINT ck_ride_earnings_commission CHECK (commission BETWEEN 0 AND gross),
    CONSTRAINT ck_ride_earnings_net CHECK (net = gross - commission)
);
CREATE INDEX idx_ride_earnings_driver_earned ON ride_earnings (driver_id, earned_at DESC);

-- Rating tags (fixed vocabulary enforced by the application enum); at most 5 per rating.
ALTER TABLE ratings ADD COLUMN tags text[] NOT NULL DEFAULT '{}';
ALTER TABLE ratings ADD CONSTRAINT ck_ratings_tags_size CHECK (cardinality(tags) <= 5);

-- Low ratings raise an admin review flag through the existing ride_flags mechanism.
ALTER TABLE ride_flags DROP CONSTRAINT ride_flags_type_check;
ALTER TABLE ride_flags ADD CONSTRAINT ride_flags_type_check CHECK (type IN
    ('EARLY_END', 'COMPLETION_FAR_FROM_DESTINATION', 'PIN_LOCKOUT', 'CASH_DISPUTE',
     'RIDER_SAFETY_CANCEL', 'RIDER_CONFIRMED_ARRIVAL', 'LOW_RATING', 'REPEATED_LOW_RATING'));

-- Commission rate in basis points (1500 = 15 percent). Deliberately seeded EMPTY: an empty value means
-- "not configured" (no commission, net = gross, commissionConfigured=false). The owner sets the real
-- rate through Admin config (docs/api/driver-earnings-ratings.md ticket T10); we never guess one.
INSERT INTO configuration (key, value, value_type, scope, description) VALUES
    ('earnings.commission.bps', '', 'INTEGER', 'GLOBAL',
        'Lady Go commission per ride in basis points (1500 = 15 percent). Empty = not configured: no commission is applied'),
    ('ratings.window.days', '7', 'INTEGER', 'GLOBAL',
        'Days after ride completion during which either party may rate it'),
    ('ratings.submit.max_per_hour', '10', 'INTEGER', 'GLOBAL',
        'Max ratings one user may submit per hour (anti-bombing)');
