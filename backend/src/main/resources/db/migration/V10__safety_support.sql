-- V10: safety incidents (SOS, reports) and trip sharing.

CREATE TABLE safety_incidents (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    ride_id         uuid        NOT NULL REFERENCES rides (id),
    reporter_id     uuid        NOT NULL REFERENCES users (id),
    type            varchar(20) NOT NULL CHECK (type IN ('SOS', 'REPORT_DRIVER', 'REPORT_CUSTOMER', 'OTHER')),
    severity        varchar(10) NOT NULL DEFAULT 'MEDIUM' CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    status          varchar(15) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'ESCALATED', 'RESOLVED')),
    location        geography(Point, 4326),
    notes           text,
    acknowledged_by uuid REFERENCES users (id),
    acknowledged_at timestamptz,
    resolved_by     uuid REFERENCES users (id),
    resolved_at     timestamptz,
    -- While true, the retention job must keep this incident's ride data (location history, documents).
    legal_hold      boolean     NOT NULL DEFAULT false,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_safety_incidents_status ON safety_incidents (status, severity, created_at);
CREATE INDEX idx_safety_incidents_ride ON safety_incidents (ride_id);
CREATE TRIGGER trg_safety_incidents_updated_at BEFORE UPDATE ON safety_incidents
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Append-only timeline of everything done about an incident.
CREATE TABLE safety_incident_events (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id uuid        NOT NULL REFERENCES safety_incidents (id),
    actor_id    uuid REFERENCES users (id),
    event_type  varchar(30) NOT NULL,
    notes       text,
    metadata    jsonb,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_safety_incident_events_incident ON safety_incident_events (incident_id, created_at);
CREATE TRIGGER trg_safety_incident_events_append_only BEFORE UPDATE ON safety_incident_events
    FOR EACH ROW EXECUTE FUNCTION prevent_update();

-- Only the hash of the share token is stored; the raw token exists only in the link given to the customer.
CREATE TABLE trip_shares (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    ride_id    uuid        NOT NULL REFERENCES rides (id),
    created_by uuid        NOT NULL REFERENCES users (id),
    token_hash varchar(64) NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_trip_shares_token UNIQUE (token_hash)
);
CREATE INDEX idx_trip_shares_ride ON trip_shares (ride_id);
