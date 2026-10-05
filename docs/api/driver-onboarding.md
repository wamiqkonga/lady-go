# Lady Go — Driver Onboarding, Rider Verification, FileStorage (Phase 5)

Status: v0.2 · **Approved 2026-10-05** (decisions below, superseding v0.1's rider-admin-review design).
Implementation proceeds against this version.

Scope: `drivers`, `vehicles`, `customers` (verification additions), a new `com.ladygo.storage` module
(`FileStorage`, ADR-009), a new `com.ladygo.verification` module (self-declarations, ride selfies,
eligibility reports — shared concepts across rider and driver), and `com.ladygo.admin` (driver-only
review endpoints). Builds on Phase 2 schema (`drivers`, `vehicles`, `driver_documents` —
`V4__drivers_vehicles_documents.sql`) and the Phase 3 append-only `audit_logs` table
(`V11__notifications_audit.sql`), which already does everything "admin review queue with audit log"
needs — no new audit infrastructure.

**Explicit scope boundary**: this phase builds document/selfie/declaration submission, driver-only
admin review, `FileStorage`, the *real* `EligibilityPolicy`, vehicle info, and booking-scoped
eligibility reporting. It does **not** build the driver "go online" toggle or matching (Phase 6) — the
re-verification *blocking* rules (§6) are designed here as data plus a query Phase 6's go-online
endpoint will consult; they do not fire from anywhere yet because there is nowhere for them to fire
from.

## 0. D5 decision (2026-10-05, revised)

No Aadhaar. **Drivers** provide: driving licence (photo + number + expiry), a live selfie, phone number
(already have), vehicle registration number + RC photo + a vehicle photo showing the plate, insurance
photo + expiry, a self-declaration, and (required only before public launch, §6) police verification.
**Riders** provide: phone number (already have), the versioned self-declaration, and a live selfie
captured fresh for each booking (§4) — **no admin review for riders at all**. There is no government
gender data source anywhere in this design; stated plainly as a residual risk in §8.

Minors stay on the roadmap default (`checkCompanions` continues returning `allowed()`
unconditionally) — not decided here.

## 1. `FileStorage` abstraction (ADR-009) — unchanged from v0.1

```java
public interface FileStorage {
    StoredFile upload(String key, InputStream content, long sizeBytes, String contentType);
    URI presignedDownloadUrl(String key, Duration ttl);
    void delete(String key);
}

record StoredFile(String key, long sizeBytes, String contentType) {}
```

- Object keys are opaque, server-generated UUIDs, never derived from a subject's ID.
- Presigned, short-lived, download-only access; buckets/directories are never public.
- **Prod**: AWS S3, SSE-S3/SSE-KMS. **Dev/test**: `LocalFileStorage`, `@Profile({"dev","test"})`, local
  filesystem + a small signed-path controller. **Staging / self-hosted**: S3 SDK against a configurable
  endpoint — same code path as prod.
- **MinIO doesn't pull anymore** (checked: `docker pull minio/minio` → access denied;
  `quay.io/minio/minio` → 401; confirmed not a sandbox issue — an unrelated image pulled cleanly
  immediately after). **`localstack/localstack`** (recommended, has a Testcontainers Java module) and
  `adobe/s3mock` both pull cleanly and are proposed as the replacement.

## 2. Schema changes (new migration, `V16`)

```sql
-- driver_documents: new document types, re-verification tracking, retention.
ALTER TABLE driver_documents DROP CONSTRAINT driver_documents_type_check;
ALTER TABLE driver_documents ADD CONSTRAINT driver_documents_type_check
    CHECK (type IN ('GOVERNMENT_ID','LICENSE','VEHICLE_REGISTRATION','VEHICLE_PHOTO','INSURANCE',
                     'FITNESS_CERTIFICATE','POLLUTION_CERTIFICATE','POLICE_VERIFICATION','PERMIT',
                     'PHOTO','SELFIE','OTHER'));
ALTER TABLE driver_documents ADD COLUMN reason varchar(20) NOT NULL DEFAULT 'INITIAL'
    CHECK (reason IN ('INITIAL','RANDOM_REVERIFICATION','REPORTED'));
ALTER TABLE driver_documents ADD COLUMN content_deleted_at timestamptz;

-- Re-verification blocking state (consulted by Phase 6's go-online endpoint, not enforced by anything
-- yet - see the scope boundary above). NULL = no pending re-verification.
ALTER TABLE drivers ADD COLUMN reverification_requested_at timestamptz;
ALTER TABLE drivers ADD COLUMN reverification_blocks_online_at timestamptz;
ALTER TABLE drivers ADD COLUMN reverification_reason varchar(20)
    CHECK (reverification_reason IN ('RANDOM_REVERIFICATION','REPORTED'));

-- Versioned legal text, shown and recorded verbatim at acceptance time (riders and drivers both use this).
CREATE TABLE declaration_versions (
    version      varchar(20) PRIMARY KEY,
    text         text        NOT NULL,
    published_at timestamptz NOT NULL DEFAULT now()
);

-- One acceptance record per subject per declaration version.
CREATE TABLE self_declarations (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_type        varchar(10) NOT NULL CHECK (subject_type IN ('CUSTOMER','DRIVER')),
    subject_id          uuid        NOT NULL,
    declaration_version varchar(20) NOT NULL REFERENCES declaration_versions (version),
    accepted_at         timestamptz NOT NULL DEFAULT now(),
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_self_declarations_subject_version UNIQUE (subject_type, subject_id, declaration_version)
);
CREATE INDEX idx_self_declarations_subject ON self_declarations (subject_type, subject_id);

-- A rider's live selfie, captured fresh per booking (NOT a one-time admin-reviewed artifact - §4).
-- Consumed by exactly one booking; booking_id starts NULL (just captured) and is set atomically when
-- a booking actually uses it, which is also what prevents reusing one capture across two bookings.
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

-- Eligibility report, scoped to a shared booking (never a free-standing subjectId - that's what makes
-- "only between users who shared a booking" a schema-level guarantee, not just an API-layer check).
CREATE TABLE eligibility_reports (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id    uuid        NOT NULL REFERENCES bookings (id),
    reporter_id   uuid        NOT NULL REFERENCES users (id),
    subject_type  varchar(10) NOT NULL CHECK (subject_type IN ('CUSTOMER','DRIVER')),
    subject_id    uuid        NOT NULL,
    reason        text        NOT NULL,
    auto_created  boolean     NOT NULL DEFAULT false,  -- true for the driver-cancel-without-penalty path (§4)
    status        varchar(15) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','REVIEWED','DISMISSED','ACTIONED')),
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
    ('verification.ride_selfie.max_age.minutes', '30', 'INTEGER', 'GLOBAL', 'How long a captured ride selfie stays usable for a new booking before it is considered stale'),
    ('vehicle.max_age.years', '8', 'INTEGER', 'GLOBAL', 'Vehicles older than this (by manufacture_year) are rejected at submission'),
    ('driver.police_verification.required', 'false', 'BOOLEAN', 'GLOBAL', 'Flip to true before public launch (D5/§6) - gates driver approval on a POLICE_VERIFICATION document'),
    ('eligibility_report.rate.max_attempts', '5', 'INTEGER', 'GLOBAL', 'Max eligibility reports per reporter per window'),
    ('eligibility_report.rate.window.hours', '24', 'INTEGER', 'GLOBAL', 'Rolling window for the above limit'),
    ('driver.reverification.random.grace.hours', '24', 'INTEGER', 'GLOBAL', 'Grace period before a random re-verification blocks going online (report-triggered blocks immediately - no grace)');
```

`EnumSchemaParityTest` (existing) needs the new Java enums kept in lock-step with these CHECK
constraints, same discipline as every other enum in this codebase.

## 3. Driver onboarding and admin review — riders are no longer in this flow at all

```
POST   /api/v1/drivers/me/documents       multipart: type, file
                                           type ∈ LICENSE | VEHICLE_REGISTRATION | VEHICLE_PHOTO |
                                                   INSURANCE | FITNESS_CERTIFICATE |
                                                   POLLUTION_CERTIFICATE | POLICE_VERIFICATION |
                                                   GOVERNMENT_ID | PERMIT | PHOTO
GET    /api/v1/drivers/me/documents
POST   /api/v1/drivers/me/selfie          multipart: file (live-capture only, client-enforced - §8)
POST   /api/v1/drivers/me/declaration     { declarationVersion }
PUT    /api/v1/drivers/me/vehicle         registrationNumber, vehicleType, make, model, color, manufactureYear
GET    /api/v1/drivers/me/vehicle
```

`PUT /vehicle` rejects (`422 VALIDATION_FAILED`) a `manufactureYear` older than
`vehicle.max_age.years` (default 8) at submission time, not left for an admin to notice later.
`INSURANCE`'s `expires_at` reuses the column `driver_documents` already has from Phase 2.

An admin approves the **driver as a whole** (flips `drivers.verification_status` to `APPROVED`) after
reviewing: `LICENSE` + `VEHICLE_REGISTRATION` + `VEHICLE_PHOTO` + `INSURANCE` + `SELFIE` all `APPROVED`,
the vehicle's own `verification_status = APPROVED`, a `self_declarations` row at the current version,
and — **only once `driver.police_verification.required` is flipped to `true` before public launch** —
a `POLICE_VERIFICATION` document also `APPROVED`. Pre-launch, that last requirement simply doesn't
apply; the config flag is the switch, not a code change, when the time comes.

```
GET  /api/v1/admin/driver-documents?status=PENDING
POST /api/v1/admin/driver-documents/{id}/approve   { note? }
POST /api/v1/admin/driver-documents/{id}/reject    { note }
GET  /api/v1/admin/eligibility-reports?status=OPEN
POST /api/v1/admin/eligibility-reports/{id}/resolve   { status: REVIEWED | DISMISSED | ACTIONED, note }
```

**Admin review is driver-documents-and-eligibility-reports only.** There is no
`customer-verifications` admin queue — riders were removed from admin review entirely (§4). Every
approve/reject/resolve writes one `AuditLog` row (existing `V11` table — actor, action,
`target_type`/`target_id`, metadata with before/after status and the note).

## 4. Riders: no admin review — self-declaration once, a fresh selfie every booking

```
POST /api/v1/customers/me/declaration     { declarationVersion }   - one-time per version, as before
POST /api/v1/customers/me/ride-selfies    multipart: file (live-capture only)
                                           → { rideSelfieId, capturedAt }
POST /api/v1/bookings                     BookingRequest gains a required field: rideSelfieId
```

A rider is eligible to book once she has: accepted the current `declaration_versions` version
(one-time, checked by `EligibilityPolicy.checkCustomerEligibility`, same as a driver's self-declaration
check), **and** supplied a fresh, unused `rideSelfieId` with *this* booking request (checked directly in
`BookingService.create`, not through `EligibilityPolicy` — it's a per-request artifact, not an
account-level policy decision). "Fresh" means captured within
`verification.ride_selfie.max_age.minutes` (default 30) of the booking request; "unused" means its
`booking_id` is still `NULL`. `BookingService.create` atomically claims it (`UPDATE ride_selfies SET
booking_id = :id WHERE id = :selfieId AND booking_id IS NULL`) in the same transaction as the booking
insert — the `uq_ride_selfies_booking` constraint plus that conditional update is what makes "exactly
one booking per selfie" race-safe, same pattern as the booking idempotency key already uses a unique
constraint rather than trusting a check-then-act read.

**No `customer_verifications` table, no admin review, no "pending" account state for riders.** This is
the headline change from v0.1: a rider's eligibility is never gated on a human looking at her — only on
whether she completed the declaration and supplied a selfie for this specific ride.

**Delivery, visibility, and lifecycle of the ride selfie** — read carefully, this is the sensitive part:

- Visible to **only the driver who accepted** that specific booking, **only after acceptance**
  (`booking.status.ordinal() >= DRIVER_ACCEPTED.ordinal()`), **hidden again once the ride starts**
  (`booking.status.ordinal() >= RIDE_STARTED.ordinal()`) — exactly the same ordinal-comparison idiom
  `BookingService.toDetail` already uses for the ride PIN.
- Before acceptance, an offer shows only a verified-rider badge (she passed the declaration+selfie
  check) and her rating — never the image itself.
- `GET /api/v1/drivers/me/bookings/{bookingId}/rider-selfie` — authorization: caller must be the
  `drivers` row whose `id` equals `booking.driverId` (ownership, not just "any driver"), status window
  as above. On success: the backend renders a **driver-ID-watermarked derivative** (Java's own
  `Graphics2D`/`ImageIO`, no new dependency) on the fly, uploads it to `FileStorage` at a throwaway key,
  and returns a `presignedDownloadUrl` with a short TTL (proposed 60s) — a literal short-lived signed
  URL, not a raw stream. The derivative key is cleaned up by the same retention job as everything else
  (§7); nothing waits on that for correctness, since the URL itself expires in 60s regardless.
- **Android `FLAG_SECURE`**: a note for the Android app phase, not this backend — the screen showing
  this image should set `FLAG_SECURE` to block screenshots/screen-recording. Can't be enforced from the
  backend; recorded here so it isn't lost before that phase.
- Retention/encryption: identical rules to every other verification artifact (§7) — encrypted at rest,
  deleted after `verification.document.retention.days`, decision trail (none, for ride selfies — there
  is no review decision) isn't relevant here; only `captured_at`/`content_deleted_at` matter.

`checkCompanions(BookingRequest)` is unchanged — still `allowed()` unconditionally.

## 5. Driver cancellation for an eligibility concern, and eligibility reports

```
POST /api/v1/drivers/me/bookings/{id}/cancel-eligibility-concern   { reason }
POST /api/v1/bookings/{id}/eligibility-report                      { reason }   (driver or customer on that booking)
```

- **Driver cancel-without-penalty**: caller must be the assigned driver (`booking.driverId`), booking
  status before `RIDE_STARTED` (same "no cancelling an active ride" rule as the customer path).
  Transitions to `CANCELLED` via the existing `BookingStateMachine` with `actorType = DRIVER` and a
  cancellation-fee of `0` unconditionally — this is not the customer's fault, nothing is charged.
  **Auto-creates** an `eligibility_reports` row: `bookingId`, `reporterId = driver's userId`,
  `subjectType = CUSTOMER`, `subjectId = booking.customerId`, `reason` from the request body,
  `autoCreated = true`.
- **Eligibility report endpoint** (both the auto-created path above and a direct call use the same
  underlying service method): caller must be either the booking's customer or its driver; the *other*
  party on that same booking becomes the subject automatically — there is no free-form `subjectId`
  input, so "only between users who shared a booking" is a property of the data model (the `booking_id`
  FK), not just something the API layer happens to check today.
- **Identified to admins, anonymous to the reported person**: `reporter_id` is always recorded and
  always visible to admins; no endpoint anywhere returns a report's `reporter_id`, or even the fact that
  a report exists, to the `subject_id` side. The subject never sees reports about themselves.
- **Rate-limited**: `RateLimiter`, bucket `eligibility_report:{reporterId}`,
  `eligibility_report.rate.max.attempts` per `eligibility_report.rate.window.hours` (defaults 5/24h) —
  same mechanism as every other rate limit in this codebase.
- If `subjectType = DRIVER`, the report immediately sets `drivers.reverification_blocks_online_at =
  now()` and `reverification_reason = 'REPORTED'` (§6) — report-triggered blocking is immediate, no
  grace period. If `subjectType = CUSTOMER`, nothing is auto-enforced (riders have no "online" gate to
  block) — it just sits `OPEN` in the admin queue for manual follow-up, which may include the existing
  account-status tools.

## 6. Driver re-verification — timing rules, as agreed

```
POST /api/v1/admin/drivers/{id}/trigger-reverification   { reason: RANDOM_REVERIFICATION }
```

(The `REPORTED` reason is never triggered by this endpoint — it's set automatically by §5's report
flow, never by direct admin action, since an admin resolving a report already has the
approve/reject-document tools to act on a driver.)

- **Report-triggered**: blocks going online **immediately** — `reverification_blocks_online_at = now()`
  at the moment the report lands (§5).
- **Random periodic** (admin-triggered via the endpoint above): a **24-hour grace period** —
  `reverification_blocks_online_at = now() + driver.reverification.random.grace.hours` (default 24h),
  giving the driver time to submit a fresh `SELFIE` document before anything blocks.
- **Never interrupts an active ride, either way**: these fields are only ever *consulted*, by Phase 6's
  future go-online transition (`OFFLINE → ONLINE`) — nothing in this phase actively forces a driver who
  is already `ON_RIDE` or `ONLINE` offline. A driver mid-ride keeps driving; the block only stops their
  *next* attempt to go online after the relevant timestamp has passed. This phase ships the data model
  and a `DriverReverificationService.isBlockedFromGoingOnline(driverId, now)` query Phase 6 will call —
  not an enforcement loop, because there's no online-toggle endpoint yet for it to interrupt.
- Submitting a new, `APPROVED` `SELFIE` document clears both `reverification_blocks_online_at` and
  `reverification_requested_at` (admin approval of that document is what resolves the re-verification,
  same review mechanism as everything else in §3).

## 7. Retention and deletion — unchanged in mechanism, updated in scope

- `verification.document.retention.days` (default 90, from `reviewed_at`) still governs
  `driver_documents` bytes.
- Ride selfies (§4) have no review step, so their deletion clock runs from `captured_at`, not
  `reviewed_at` — same retention config key, same scheduled `@Scheduled` job, just a different anchor
  column per table.
- `self_declarations` rows are never deleted (legal record of consent).
- **Flagged to legal, not decided here (§9)**: some data in this system — audit logs, eligibility
  reports, declaration acceptances — may need a **minimum** one-year retention for regulatory reasons,
  which would *override* a shorter default-deletion schedule for those specific tables. The 90-day
  figure above is about convenience/storage-cost deletion of raw image bytes, not a ceiling on how long
  the *decision record* is kept — those are already designed to be kept indefinitely (§3), so this
  tension is really about audit_logs/eligibility_reports rows themselves, not images.

## 8. Privacy notes — read before approving

- No government gender data source exists or is planned. Eligibility rests on a self-declaration plus
  (for drivers) an admin's visual judgement — stated as a residual risk, not solved here.
- "Live-capture-only" cannot be enforced server-side — the server only ever receives bytes. Client UI
  should make it as hard as practical (no gallery picker); this design doesn't claim proof of liveness.
- Selfies (both the one-time driver selfie and the per-booking rider selfie) are among the most
  sensitive data this system stores: encrypted at rest, never a public key, presigned short-TTL download
  only, and for the rider's case, visible to exactly one driver for exactly one ride window (§4) — never
  to other drivers, never returned in any list/history endpoint.
- "False declaration is grounds for permanent removal" is a product/ToS enforcement action — this
  design durably records acceptance (§2) and gives admins the tools to act on a found-false declaration
  (reject/report flow); the removal decision itself is a human one.

## 9. Open questions and later phases — not decided here

1. **Panic button and vehicle tracking** — ties into the already-planned SOS work (roadmap Phase 10);
   not designed in this document.
2. **DPDP-style specific consent notice and withdrawal link** — the self-declaration (§2) is a
   liability/eligibility statement, not a data-protection consent notice; a separate, specific consent
   flow (and a working withdrawal link) is needed before launch and isn't designed here.
3. **72-hour breach-notification runbook** — an operational document, not a schema or endpoint; needed
   before launch, not drafted here.
4. **Retention defaults reconciled by legal** (§7) — the 90-day image-deletion default and the
   "some records may need a one-year minimum" tension both need a legal sign-off pass, not an engineering
   default.
5. **Minors** — accompanied minors stay on the roadmap default (`checkCompanions` unchanged) until you
   decide that sub-question separately; restated here so it doesn't look dropped.
6. **Legal review before launch generally** (data protection + transport licensing) — who does it, and
   by when (roadmap D11).

Design-first, as before — this version reflects your decisions above and is approved for
implementation; §9's items are explicitly deferred, not silently assumed.
