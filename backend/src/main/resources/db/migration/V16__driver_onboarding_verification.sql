-- V16: Phase 5 - driver onboarding document types, re-verification tracking, self-declarations,
-- ride selfies, eligibility reports. docs/api/driver-onboarding.md.

ALTER TABLE driver_documents DROP CONSTRAINT driver_documents_type_check;
ALTER TABLE driver_documents ADD CONSTRAINT driver_documents_type_check
    CHECK (type IN ('GOVERNMENT_ID', 'LICENSE', 'VEHICLE_REGISTRATION', 'VEHICLE_PHOTO', 'INSURANCE',
                     'FITNESS_CERTIFICATE', 'POLLUTION_CERTIFICATE', 'POLICE_VERIFICATION', 'PERMIT',
                     'PHOTO', 'SELFIE', 'OTHER'));
ALTER TABLE driver_documents ADD COLUMN reason varchar(30) NOT NULL DEFAULT 'INITIAL'
    CHECK (reason IN ('INITIAL', 'RANDOM_REVERIFICATION', 'REPORTED'));
ALTER TABLE driver_documents ADD COLUMN content_deleted_at timestamptz;

-- Re-verification blocking state, consulted (not yet enforced - no go-online endpoint exists until
-- Phase 6) by a future online-toggle transition. NULL reverification_blocks_online_at = nothing pending.
ALTER TABLE drivers ADD COLUMN reverification_requested_at timestamptz;
ALTER TABLE drivers ADD COLUMN reverification_blocks_online_at timestamptz;
ALTER TABLE drivers ADD COLUMN reverification_reason varchar(30)
    CHECK (reverification_reason IN ('RANDOM_REVERIFICATION', 'REPORTED'));

CREATE TABLE declaration_versions (
    version      varchar(20) PRIMARY KEY,
    text         text        NOT NULL,
    published_at timestamptz NOT NULL DEFAULT now()
);
-- Placeholder legal text - legal review before launch (docs/api/driver-onboarding.md section 9) will
-- replace this with the actual reviewed wording; the mechanism (versioned, immutable, referenced by
-- self_declarations) does not depend on the exact words.
INSERT INTO declaration_versions (version, text) VALUES
    ('v1', 'I declare that the information I have provided is accurate and that I meet Lady Go''s eligibility requirements for this account. I understand that a false declaration is grounds for permanent removal from the platform.');

CREATE TABLE self_declarations (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_type        varchar(10) NOT NULL CHECK (subject_type IN ('CUSTOMER', 'DRIVER')),
    subject_id          uuid        NOT NULL,
    declaration_version varchar(20) NOT NULL REFERENCES declaration_versions (version),
    accepted_at         timestamptz NOT NULL DEFAULT now(),
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_self_declarations_subject_version UNIQUE (subject_type, subject_id, declaration_version)
);
CREATE INDEX idx_self_declarations_subject ON self_declarations (subject_type, subject_id);

-- A rider's live selfie, captured fresh per booking - not a one-time, admin-reviewed artifact.
CREATE TABLE ride_selfies (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id        uuid        NOT NULL REFERENCES customers (id),
    booking_id         uuid REFERENCES bookings (id),
    selfie_storage_key varchar(500) NOT NULL,
    captured_at        timestamptz NOT NULL DEFAULT now(),
    content_deleted_at timestamptz,
    CONSTRAINT uq_ride_selfies_booking UNIQUE (booking_id)
);
CREATE INDEX idx_ride_selfies_customer ON ride_selfies (customer_id, captured_at DESC);

-- Eligibility report, always scoped to a shared booking - "only between users who shared a booking"
-- is a schema-level guarantee (the booking_id FK), not just an API-layer check.
CREATE TABLE eligibility_reports (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id    uuid        NOT NULL REFERENCES bookings (id),
    reporter_id   uuid        NOT NULL REFERENCES users (id),
    subject_type  varchar(10) NOT NULL CHECK (subject_type IN ('CUSTOMER', 'DRIVER')),
    subject_id    uuid        NOT NULL,
    reason        text        NOT NULL,
    auto_created  boolean     NOT NULL DEFAULT false,
    status        varchar(15) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'REVIEWED', 'DISMISSED', 'ACTIONED')),
    reviewer_id   uuid REFERENCES users (id),
    review_note   text,
    reviewed_at   timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_eligibility_reports_status ON eligibility_reports (status, created_at);
CREATE INDEX idx_eligibility_reports_booking ON eligibility_reports (booking_id);
CREATE TRIGGER trg_eligibility_reports_updated_at BEFORE UPDATE ON eligibility_reports
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

INSERT INTO configuration (key, value, value_type, scope, description) VALUES
    ('verification.document.retention.days', '90', 'INTEGER', 'GLOBAL', 'Days after review before document/selfie bytes are purged'),
    ('verification.ride_selfie.max_age.minutes', '30', 'INTEGER', 'GLOBAL', 'How long a captured ride selfie stays usable for a new booking before it is stale'),
    ('vehicle.max_age.years', '8', 'INTEGER', 'GLOBAL', 'Vehicles older than this (by manufacture_year) are rejected at submission'),
    ('driver.police_verification.required', 'false', 'BOOLEAN', 'GLOBAL', 'Flip to true before public launch - gates driver approval on a POLICE_VERIFICATION document'),
    ('eligibility_report.rate.max.attempts', '5', 'INTEGER', 'GLOBAL', 'Max eligibility reports per reporter per window'),
    ('eligibility_report.rate.window.hours', '24', 'INTEGER', 'GLOBAL', 'Rolling window for the above limit'),
    ('driver.reverification.random.grace.hours', '24', 'INTEGER', 'GLOBAL', 'Grace period before a random re-verification blocks going online (report-triggered has none)'),
    ('verification.upload.max_size.bytes', '8388608', 'INTEGER', 'GLOBAL', 'Max accepted size for any document/selfie upload (8 MiB)'),
    ('verification.rider_selfie.view_url.ttl.seconds', '60', 'INTEGER', 'GLOBAL', 'How long the signed URL to a watermarked rider-selfie derivative stays valid');

