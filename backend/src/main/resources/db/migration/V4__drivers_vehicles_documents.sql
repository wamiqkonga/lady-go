-- V4: drivers, vehicles, driver documents.

CREATE TABLE drivers (
    id                           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                      uuid        NOT NULL REFERENCES users (id),
    city_id                      uuid        NOT NULL REFERENCES cities (id),
    verification_status          varchar(20) NOT NULL DEFAULT 'NOT_SUBMITTED'
        CHECK (verification_status IN ('NOT_SUBMITTED', 'PENDING_REVIEW', 'APPROVED', 'REJECTED', 'SUSPENDED')),
    availability_status          varchar(20) NOT NULL DEFAULT 'OFFLINE'
        CHECK (availability_status IN ('OFFLINE', 'ONLINE', 'BUSY', 'ON_RIDE', 'SUSPENDED')),
    -- Last known position only (sampled). Full trails exist only per ride in location_history.
    current_location             geography(Point, 4326),
    current_location_at          timestamptz,
    rating_avg                   numeric(3, 2) NOT NULL DEFAULT 0 CHECK (rating_avg BETWEEN 0 AND 5),
    rating_count                 integer     NOT NULL DEFAULT 0 CHECK (rating_count >= 0),
    total_rides                  integer     NOT NULL DEFAULT 0 CHECK (total_rides >= 0),
    license_number_enc           text,       -- encrypted at application level
    license_expiry               date,
    identity_verification_status   varchar(20) NOT NULL DEFAULT 'NOT_SUBMITTED'
        CHECK (identity_verification_status IN ('NOT_SUBMITTED', 'PENDING', 'VERIFIED', 'FAILED')),
    background_verification_status varchar(20) NOT NULL DEFAULT 'NOT_SUBMITTED'
        CHECK (background_verification_status IN ('NOT_SUBMITTED', 'PENDING', 'VERIFIED', 'FAILED')),
    verified_by                  uuid REFERENCES users (id),
    verified_at                  timestamptz,
    rejection_reason             text,
    version                      bigint      NOT NULL DEFAULT 0,
    created_at                   timestamptz NOT NULL DEFAULT now(),
    updated_at                   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_drivers_user UNIQUE (user_id),
    -- Safety invariant: only an approved driver can ever be online, busy or on a ride.
    CONSTRAINT ck_drivers_online_requires_approval CHECK (
        availability_status IN ('OFFLINE', 'SUSPENDED') OR verification_status = 'APPROVED')
);
CREATE INDEX idx_drivers_location ON drivers USING gist (current_location);
CREATE INDEX idx_drivers_city_availability ON drivers (city_id, availability_status);
CREATE INDEX idx_drivers_verification ON drivers (verification_status);
CREATE TRIGGER trg_drivers_updated_at BEFORE UPDATE ON drivers
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE vehicles (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    driver_id           uuid        NOT NULL REFERENCES drivers (id),
    registration_number varchar(20) NOT NULL,
    vehicle_type        varchar(20) NOT NULL CHECK (vehicle_type IN ('CAB', 'SUV', 'AUTO', 'BIKE')),
    make                varchar(50),
    model               varchar(50),
    color               varchar(30),
    capacity            smallint    NOT NULL DEFAULT 4 CHECK (capacity > 0),
    manufacture_year    smallint CHECK (manufacture_year BETWEEN 1990 AND 2100),
    verification_status varchar(20) NOT NULL DEFAULT 'PENDING'
        CHECK (verification_status IN ('PENDING', 'APPROVED', 'REJECTED', 'SUSPENDED')),
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_vehicles_registration ON vehicles (upper(registration_number));
CREATE INDEX idx_vehicles_driver ON vehicles (driver_id);
CREATE TRIGGER trg_vehicles_updated_at BEFORE UPDATE ON vehicles
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- KYC documents live in object storage; only the storage key is kept here.
CREATE TABLE driver_documents (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    driver_id   uuid         NOT NULL REFERENCES drivers (id),
    vehicle_id  uuid REFERENCES vehicles (id),
    type        varchar(30)  NOT NULL
        CHECK (type IN ('GOVERNMENT_ID', 'LICENSE', 'VEHICLE_REGISTRATION', 'INSURANCE', 'PERMIT', 'PHOTO', 'OTHER')),
    storage_key varchar(500) NOT NULL,
    status      varchar(20)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    reviewer_id uuid REFERENCES users (id),
    review_note text,
    reviewed_at timestamptz,
    expires_at  date,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_driver_documents_driver ON driver_documents (driver_id);
CREATE INDEX idx_driver_documents_status ON driver_documents (status);
CREATE INDEX idx_driver_documents_expires ON driver_documents (expires_at) WHERE expires_at IS NOT NULL;
CREATE TRIGGER trg_driver_documents_updated_at BEFORE UPDATE ON driver_documents
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
