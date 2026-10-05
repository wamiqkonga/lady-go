# Lady Go — Driver Onboarding, Rider/Driver Verification, FileStorage (Phase 5)

Status: v0.1 · **Draft — awaiting your approval.** Nothing in this document is implemented. No code
has been written against it.

Scope: `drivers`, `vehicles`, `customers` (verification additions), a new `com.ladygo.storage` module
(`FileStorage`, ADR-009), a new `com.ladygo.verification` module (self-declarations, shared by both
rider and driver subjects), and `com.ladygo.admin` (review endpoints). Builds on Phase 2 schema
(`drivers`, `vehicles`, `driver_documents` — `V4__drivers_vehicles_documents.sql`) and the Phase 11
append-only `audit_logs` table (`V11__notifications_audit.sql`), which already does everything "admin
review queue with audit log" needs — no new audit infrastructure.

**Explicit scope boundary**: this phase builds document/selfie/declaration submission, admin review,
`FileStorage`, the *real* `EligibilityPolicy` (replacing Phase 4's placeholder), vehicle info, and the
report-not-eligible path. It does **not** build the driver "go online" toggle or matching (Phase 6) —
random re-verification is designed here as data plus a query ("is a fresh check due for this driver"),
but is wired into an actual online-toggle endpoint only when Phase 6 builds that endpoint.

## 0. D5 decision (recorded here, 2026-10-05 — supersedes the roadmap's placeholder default)

No Aadhaar. **Drivers** provide: driving licence (photo + number + expiry), a live selfie, phone number
(already have, Phase 3), vehicle registration number, and a self-declaration. **Riders** provide: phone
number (already have), a live selfie, and a self-declaration. Eligibility rests on **human admin
review** — the selfie matched to the licence photo for drivers, plus the recorded self-declaration.
There is no government gender data source anywhere in this design; stated plainly as a residual risk in
§8, not solved by this document.

Minors stay on the roadmap default (`checkCompanions` continues returning `allowed()` unconditionally)
until you decide D5's companion sub-question separately — not touched here.

## 1. `FileStorage` abstraction (ADR-009)

```java
public interface FileStorage {
    StoredFile upload(String key, InputStream content, long sizeBytes, String contentType);
    URI presignedDownloadUrl(String key, Duration ttl);
    void delete(String key);
}

record StoredFile(String key, long sizeBytes, String contentType) {}
```

- **Object keys are opaque, server-generated UUIDs** (e.g. `verification/{random-uuid}`), never derived
  from `driverId`/`customerId` directly — guessing a real subject's ID must not let anyone guess their
  file key. The DB row (`driver_documents.storage_key`, `customer_verifications.selfie_storage_key`) is
  the only place key-to-subject mapping exists.
- **Access is presigned, short-lived, download-only** — same ADR-009 wording as already recorded.
  Buckets/directories are never public.
- **Prod**: AWS S3, SSE-S3 (or SSE-KMS) at the bucket. **Dev/test**: local filesystem under a
  configured directory (`LocalFileStorage`), `@Profile({"dev","test"})`, mirroring the `MockMapsService`
  / `MockSmsService` pattern — a "presigned URL" in dev is just a signed path served by a small
  controller with a short TTL, not a real cloud mechanism.
- **Staging / self-hosted S3-compatible**: the S3 SDK client against a configurable endpoint URL — same
  code path as prod, different endpoint, so staging genuinely exercises the production code (not a
  separate "staging storage" implementation).

### MinIO doesn't work anymore — checked, here's what does

You asked me to check whether the MinIO image pulls for local dev/Testcontainers. **It doesn't.**
`docker pull minio/minio` → *"pull access denied, repository does not exist or may require 'docker
login'"*; the `quay.io/minio/minio` mirror → *401 Unauthorized*. This isn't a sandbox/auth problem — a
plain `docker pull postgres:16-alpine` right afterward worked cleanly. MinIO pulled its Community
Edition images from Docker Hub and Quay in 2025 as part of a licensing change.

Checked two working replacements (both pull cleanly, confirmed just now):

| Option | Notes |
|---|---|
| **`localstack/localstack`** (recommended) | `SERVICES=s3` for just S3. Has a first-class Testcontainers Java module (`LocalStackContainer`), matching this project's existing Postgres-via-Testcontainers pattern exactly. Broader AWS surface if a future phase ever needs it (e.g. SQS). |
| `adobe/s3mock` | S3 API only, lighter/faster startup. Documented fallback if LocalStack's startup time becomes annoying in the test suite. |

Proposing **LocalStack** for both `docker-compose` local dev and the integration-test container, same
role MinIO would have played.

## 2. Schema changes (new migration, `V16`)

```sql
-- driver_documents gains a SELFIE type and re-verification tracking.
ALTER TABLE driver_documents DROP CONSTRAINT driver_documents_type_check;
ALTER TABLE driver_documents ADD CONSTRAINT driver_documents_type_check
    CHECK (type IN ('GOVERNMENT_ID','LICENSE','VEHICLE_REGISTRATION','INSURANCE','PERMIT','PHOTO','SELFIE','OTHER'));
ALTER TABLE driver_documents ADD COLUMN reason varchar(20) NOT NULL DEFAULT 'INITIAL'
    CHECK (reason IN ('INITIAL','RANDOM_REVERIFICATION','REPORTED'));
ALTER TABLE driver_documents ADD COLUMN content_deleted_at timestamptz;

-- Riders' lighter verification: selfie + review only, no licence/vehicle fields.
CREATE TABLE customer_verifications (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id        uuid        NOT NULL REFERENCES customers (id),
    selfie_storage_key varchar(500) NOT NULL,
    status             varchar(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED')),
    reason             varchar(20) NOT NULL DEFAULT 'INITIAL' CHECK (reason IN ('INITIAL','RANDOM_REVERIFICATION','REPORTED')),
    reviewer_id        uuid REFERENCES users (id),
    review_note        text,
    reviewed_at        timestamptz,
    content_deleted_at timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_customer_verifications_customer ON customer_verifications (customer_id, created_at DESC);
CREATE INDEX idx_customer_verifications_status ON customer_verifications (status);

-- Versioned legal text, shown and recorded verbatim at acceptance time.
CREATE TABLE declaration_versions (
    version      varchar(20) PRIMARY KEY,
    text         text        NOT NULL,
    published_at timestamptz NOT NULL DEFAULT now()
);

-- One acceptance record per subject per declaration version (riders and drivers both use this).
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

-- "Fast report path for not eligible" - deliberately NOT safety_incidents (that table's ride_id is
-- NOT NULL; a report can happen with no ride context, e.g. from a profile view).
CREATE TABLE eligibility_reports (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_type varchar(10) NOT NULL CHECK (subject_type IN ('CUSTOMER','DRIVER')),
    subject_id  uuid        NOT NULL,
    reporter_id uuid REFERENCES users (id),
    reason      text        NOT NULL,
    status      varchar(15) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','REVIEWED','DISMISSED','ACTIONED')),
    reviewer_id uuid REFERENCES users (id),
    review_note text,
    reviewed_at timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_eligibility_reports_status ON eligibility_reports (status, created_at);
CREATE TRIGGER trg_eligibility_reports_updated_at BEFORE UPDATE ON eligibility_reports
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
```

`EnumSchemaParityTest` (existing) will need the new Java enums (`DocumentType.SELFIE`, a shared
`VerificationReason`, `EligibilityReportStatus`) kept in lock-step with these CHECK constraints, same
discipline as every other enum in this codebase.

## 3. Driver onboarding flow

Builds on the Phase 2 `drivers`/`vehicles`/`driver_documents` tables and existing
`ck_drivers_online_requires_approval` constraint (a driver can only go `ONLINE` once
`verification_status = APPROVED` — already enforced at the DB level, Phase 6 just needs to respect it).

```
POST   /api/v1/drivers/me/documents       multipart: type (LICENSE | VEHICLE_REGISTRATION | INSURANCE | ...), file
GET    /api/v1/drivers/me/documents
POST   /api/v1/drivers/me/selfie          multipart: file (live-capture only, enforced client-side - see §8)
POST   /api/v1/drivers/me/declaration     { declarationVersion }  - must match the current published version
PUT    /api/v1/drivers/me/vehicle         registrationNumber, vehicleType, make, model, color, manufactureYear
GET    /api/v1/drivers/me/vehicle
```

One active vehicle per driver assumed for this phase (a single upsert-style `PUT`, not a list) —
`vehicles.driver_id` has no uniqueness constraint in the Phase 2 schema, so this is a modeling choice
for the endpoint shape, not a schema change; replacing a vehicle later is a straightforward addition if
ever needed, not blocked by anything here.

An admin approves the **driver as a whole** (flips `drivers.verification_status` to `APPROVED`) only
after reviewing: `LICENSE` + `VEHICLE_REGISTRATION` + `SELFIE` documents all `APPROVED`, the vehicle's
own `verification_status = APPROVED`, and a `self_declarations` row at the current
`declaration_versions` version. The design doesn't auto-approve on all-green — the human decision is
the approval, same as the explicit D5 decision requires.

## 4. Rider (customer) verification flow — new in Phase 5

```
POST /api/v1/customers/me/selfie        multipart: file (live-capture only)
POST /api/v1/customers/me/declaration   { declarationVersion }
```

`EligibilityPolicy.checkCustomerEligibility(Customer)` (`com.ladygo.bookings.service`) gets its first
real implementation, replacing `DefaultEligibilityPolicy`:

```java
denied() unless:
  latest customer_verifications row for this customer has status = APPROVED, AND
  a self_declarations row exists for (CUSTOMER, customerId, currentDeclarationVersion)
```

**Flagging this plainly, not burying it**: the moment this ships, *every* customer must pass admin
review before their first booking — this blocks the entire Phase 4 booking flow for anyone not yet
reviewed, including every existing test fixture and any real account created before this phase. That's
the direct, intended consequence of "eligibility rests on human admin review," but it has an
operational dimension (review-queue throughput, turnaround time) that's a staffing/ops question, not a
code one — listed as an open question in §9.

`checkCompanions(BookingRequest)` is **unchanged** — still `allowed()` unconditionally, per your
instruction that minors stay on the roadmap default.

## 5. Licence / vehicle-registration verification — behind interfaces, manual-only for now

Per your instruction, these exist as seams now so an official government API can be added later without
touching onboarding logic, but Phase 5 ships only a manual-review implementation of each — there's no
"mock vs real" split like `MapsService` has, because there's no automated option to mock yet:

```java
public interface LicenceVerifier {
    VerificationOutcome verify(String licenceNumber, LocalDate expiry, byte[] licencePhoto);
}
public interface VehicleRegistryVerifier {
    VerificationOutcome verify(String registrationNumber);
}
```

`ManualReviewLicenceVerifier` / `ManualReviewVehicleRegistryVerifier` both always return
`VerificationOutcome.deferToAdmin()` — i.e. "no automated check ran; a human must decide" — which is
exactly today's actual process. Nothing calls these yet except as a documented extension point.

## 6. Admin review (`com.ladygo.admin`)

```
GET  /api/v1/admin/driver-documents?status=PENDING
POST /api/v1/admin/driver-documents/{id}/approve   { note? }
POST /api/v1/admin/driver-documents/{id}/reject    { note }
GET  /api/v1/admin/customer-verifications?status=PENDING
POST /api/v1/admin/customer-verifications/{id}/approve
POST /api/v1/admin/customer-verifications/{id}/reject
GET  /api/v1/admin/eligibility-reports?status=OPEN
POST /api/v1/admin/eligibility-reports/{id}/resolve   { status: REVIEWED | DISMISSED | ACTIONED, note }
POST /api/v1/eligibility-reports                       (any authenticated user) { subjectType, subjectId, reason }
```

Every approve/reject/resolve writes one `AuditLog` row — **reusing the existing append-only
`audit_logs` table from `V11`** (actor, action, `target_type`/`target_id`, metadata with before/after
status and the note). No new audit table; this one already does the job.

**Random re-verification**: an admin-triggered action that creates a fresh `PENDING` `SELFIE` /
`customer_verifications` row with `reason = RANDOM_REVERIFICATION`, leaving the subject's *current*
`APPROVED` status untouched until the new check resolves. Open question in §9: does a pending
re-verification block activity, or only a `REJECT` outcome does?

## 7. Retention and deletion

- New `configuration` keys (seeded in the same `V16` migration, `GLOBAL` scope):
  `verification.document.retention.days` (proposed default **90**, counted from `reviewed_at`).
- A scheduled job (Spring `@Scheduled`, no new infra) calls `FileStorage.delete(key)` for anything past
  retention and sets `content_deleted_at`; the row itself (status, reviewer, timestamps, review note) is
  **kept indefinitely** — the decision trail survives even after the image bytes are gone.
- `self_declarations` rows (the acceptance record, not file bytes) are never deleted — a legal record of
  consent.
- Exact retention numbers are proposals for your review, not final — see §9 (D11).

## 8. Privacy notes — read before approving

- **No government gender data source exists or is planned anywhere in this design.** The entire basis
  for eligibility is an admin's visual judgement of a selfie plus a self-declaration. This is a real,
  ongoing limitation, not a gap this document closes — stated here so it isn't a silent assumption.
- **"Live-capture-only" cannot be enforced server-side.** The server only ever receives bytes; a
  determined user can defeat a client-side camera-only capture flow by photographing a photo or using
  screen-capture tooling. The client UI should make this as hard as practical (no gallery picker,
  liveness hints), but this design doesn't claim server-side proof of liveness — there is no such proof
  without a liveness-detection vendor, not in scope here.
- Selfie images are among the most sensitive data this system stores: encrypted at rest (bucket-level
  SSE), never a public key, presigned short-TTL download only, visible only to the reviewing admin (not
  to other drivers/riders, not returned in any customer-facing API), and deleted per retention (§7).
- "False declaration is grounds for permanent removal" is a **product/ToS enforcement action**, not a
  technical one — this design's job is to durably record the acceptance (done, §2) and give admins a way
  to act on an account once a declaration is found false (the existing reject/report flow already
  covers "take action on this account"); the removal decision itself is a human one.

## 9. Open questions — not decided here, listed per your instruction

1. **RC (vehicle registration certificate)** — require a photo upload of the physical document, or is
   the registration number alone (already captured today) sufficient? Affects whether the
   `VEHICLE_REGISTRATION` document type upload is mandatory or just informational.
2. **Insurance** — required at onboarding, or deferred to a later phase? (`DocumentType.INSURANCE`
   already exists in the Phase 2 schema, unused so far.)
3. **Police verification** — required at all, and if so: another manual document type now, or an
   official API later (same "behind an interface" treatment proposed in §5 for licence/RC)?
4. **Legal review before launch** — data protection and transport-licensing sign-off: who does it, and
   by when (ties to roadmap D11)?
5. **Retention periods** — exact day counts for document/selfie bytes, declaration records, audit logs,
   and eligibility reports (D11 again) — the 90-day default in §7 is a proposal, not a decision.
6. **Pending re-verification** (§6) — does it block the driver from going online / the rider from
   booking while pending, or only a `REJECT` outcome blocks? Lower-disruption default would be "only
   `REJECT` blocks," but this is a product/safety trade-off, not mine to default silently.
7. **Anonymous eligibility reports** — is `reporter_id` required (identified reporter) or nullable
   (anonymous)? An open, unauthenticated-adjacent report channel invites nuisance reports; requiring
   identification adds friction to a safety-motivated "fast report path." Proposed default above assumes
   an authenticated reporter (`reporter_id` required); flagging as a real trade-off, not a given.

Design-first, per your instruction — stopping here for your approval before any code.
