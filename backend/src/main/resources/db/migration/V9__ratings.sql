-- V9: ratings (one per rater per ride).

CREATE TABLE ratings (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    ride_id    uuid        NOT NULL REFERENCES rides (id),
    rater_id   uuid        NOT NULL REFERENCES users (id),
    ratee_id   uuid        NOT NULL REFERENCES users (id),
    rater_role varchar(10) NOT NULL CHECK (rater_role IN ('CUSTOMER', 'DRIVER')),
    score      smallint    NOT NULL CHECK (score BETWEEN 1 AND 5),
    comment    varchar(1000),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_ratings_ride_rater UNIQUE (ride_id, rater_id),
    CONSTRAINT ck_ratings_not_self CHECK (rater_id <> ratee_id)
);
CREATE INDEX idx_ratings_ratee ON ratings (ratee_id, created_at DESC);
