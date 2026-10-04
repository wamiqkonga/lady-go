-- V3: customers, saved locations, emergency contacts.

CREATE TABLE customers (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         uuid        NOT NULL REFERENCES users (id),
    default_city_id uuid REFERENCES cities (id),
    preferences     jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_customers_user UNIQUE (user_id)
);
CREATE TRIGGER trg_customers_updated_at BEFORE UPDATE ON customers
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE saved_locations (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id uuid                  NOT NULL REFERENCES customers (id) ON DELETE CASCADE,
    label       varchar(50)           NOT NULL,
    address     text                  NOT NULL,
    location    geography(Point, 4326) NOT NULL,
    created_at  timestamptz           NOT NULL DEFAULT now(),
    CONSTRAINT uq_saved_locations_label UNIQUE (customer_id, label)
);

CREATE TABLE emergency_contacts (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id  uuid         NOT NULL REFERENCES customers (id) ON DELETE CASCADE,
    name         varchar(120) NOT NULL,
    phone        varchar(16)  NOT NULL CHECK (phone ~ '^\+[1-9][0-9]{7,14}$'),
    relationship varchar(50),
    created_at   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_emergency_contacts_phone UNIQUE (customer_id, phone)
);
