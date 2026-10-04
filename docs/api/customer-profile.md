# Lady Go — Customer Profile, Saved Locations, Emergency Contacts (Phase 4)

Status: v0.2 · **Approved 2026-10-04** (decisions C1–C6 below). Implementation proceeds against this version.

Scope: `customers` module. Builds on tables already migrated in Phase 2 (`customers`, `saved_locations`, `emergency_contacts` — `V3__customers.sql`) and the entities/repositories already written there (`Customer`, `SavedLocation`, `EmergencyContact` + their repositories, including `countByCustomerId` on both child repositories — written in Phase 2 clearly anticipating the limits this document now specifies). No schema changes.

## 1. A gap this phase must close first: customer row creation

`CustomerRepository` requires a `Customer` row keyed by `user_id` (`uq_customers_user`), but `OtpLoginService.verifyOtp` (Phase 3) only ever creates a `User` row — it never creates the matching `Customer` row for a new CUSTOMER signup. Every endpoint below needs `customers.me` to resolve, so Phase 4 adds one thing to existing Phase 3 code: when `OtpLoginService` creates a new `User` with `role = CUSTOMER`, it also creates the corresponding `Customer` row (`user_id`, `default_city_id = null`, `preferences = {}`) in the same transaction. This is a small, backward-compatible addition to `OtpLoginService`, not a redesign — called out here because it's a prerequisite, not because it changes anything about Phase 3's approved behaviour.

**Approved**, with one explicit requirement: a test proving that if the `Customer` insert fails for any reason, the `User` insert in the same call is rolled back too — no orphan `User` row left behind. This is tested with a Mockito spy on the real `CustomerRepository` bean (`@MockitoSpyBean`, Spring Boot 3.4+) configured to throw, calling the real, still-`@Transactional`-proxied `OtpLoginService`, then asserting `UserRepository.findByPhone(...)` is empty afterward — proving the *actual* transactional boundary, not the test framework's own rollback.

## 2. Profile

```
GET /api/v1/customers/me
PUT /api/v1/customers/me
```

Both require `ROLE_CUSTOMER`. The authenticated principal's `userId` resolves to a `Customer` via `findByUserId` (404 `RESOURCE_NOT_FOUND` if somehow missing — should be unreachable once §1 lands).

`GET` response combines `users` (phone, name, email, profile photo key) and `customers` (preferences, default city) — two tables, one DTO, since the API's notion of "profile" spans both:

```json
{
  "id": "<customer id>",
  "phone": "+91...",
  "phoneVerifiedAt": "...",
  "name": "...",
  "email": "...",
  "profilePhotoKey": null,
  "defaultCityId": "...",
  "preferences": {}
}
```

`PUT` body: `{ "name": "...", "email": "...", "defaultCityId": "..." }`.

**C1 — decided**: `phone` is never editable through this endpoint. Phone-number change is deferred past Phase 4 entirely (not just the re-verification flow — the whole feature). ADR-010 and `docs/product/user-flows.md` §2.6 stay unimplemented on this point until a later phase deliberately designs it.

**C2 — decided**: `email` is optional and editable with no re-verification step (no email-OTP channel exists to verify it with). It is **purely contact information** — stored, editable, shown back in the profile — and must never be usable as an authentication factor or an account-recovery channel for a CUSTOMER/DRIVER account (that stays phone+OTP only, per ADR-010 and `docs/security/auth-design.md`). This constrains any future email-verification work too: verifying the email later doesn't change what it's allowed to be used for unless that's a separate, deliberate decision. Validated as an email shape, lower-cased before the existing `uq_users_email` (case-insensitive) check — reject with `VALIDATION_FAILED` on conflict, not a 500 from the DB constraint.

`name`: required, 1–120 chars (matches `users.name varchar(120)`).

**C3 — decided**: `profilePhotoKey` is read-only in Phase 4 — the field exists (maps to `users.profile_photo_key`) but there is no upload endpoint. Photo upload needs the `FileStorage` SPI (ADR-009), deferred to Phase 5 alongside driver documents, its first real consumer.

## 3. Saved locations

```
GET    /api/v1/customers/me/saved-locations
POST   /api/v1/customers/me/saved-locations
PUT    /api/v1/customers/me/saved-locations/{id}
DELETE /api/v1/customers/me/saved-locations/{id}
```

All `ROLE_CUSTOMER`; every lookup scoped to the authenticated customer's own rows (`customer_id = :customerId` in every query — a saved location is never addressable across customers, so a mismatched `{id}` is `404 RESOURCE_NOT_FOUND`, not `403`, to avoid confirming the row exists for someone else).

```json
{ "id": "...", "label": "Home", "address": "...", "latitude": 34.08, "longitude": 74.80, "createdAt": "..." }
```

`label`: required, 1–50 chars, free text (the schema's `uq_saved_locations_label (customer_id, label)` is a per-customer unique constraint, not a fixed `home`/`work` enum — a customer can label anything). Create/update body: `{ "label", "address", "latitude", "longitude" }`; the service layer builds the `Point` from lat/lon the same way `PersistenceTestSupport.point(lon, lat)` does in tests (longitude first internally — JTS convention — but the API speaks `latitude`/`longitude` like every other map API, not lon/lat, to avoid a footgun for mobile client authors).

**Limit — C4, decided: 10**, configurable. A new `configuration` key `customer.saved_locations.max.count` (GLOBAL, INTEGER, seeded by `V14`), read through `ConfigurationService.getInt(...)`, checked via the already-written `SavedLocationRepository.countByCustomerId` before insert. On the limit being hit: `422` `LIMIT_EXCEEDED` (new — §5) naming the resource and the configured maximum, e.g. *"You can save up to 10 locations."*

## 4. Emergency contacts

```
GET    /api/v1/customers/me/emergency-contacts
POST   /api/v1/customers/me/emergency-contacts
PUT    /api/v1/customers/me/emergency-contacts/{id}
DELETE /api/v1/customers/me/emergency-contacts/{id}
```

**C5 — decided**: `PUT` edits in place (reversing the draft's delete-and-recreate proposal).

```json
{ "id": "...", "name": "...", "phone": "+91...", "relationship": "...", "createdAt": "..." }
```

`phone`: required, same E.164 pattern as `users.phone` (schema already enforces it via CHECK; validate client-side too for a fast error). `relationship`: optional free text, ≤50 chars. `uq_emergency_contacts_phone (customer_id, phone)` means the same number can't be added twice for one customer — surfaced as `409` `RESOURCE_NOT_FOUND`... no: as a `VALIDATION_FAILED` with a clear message, not a raw constraint-violation 500.

**Limit — C6, decided: 5**, configurable. `customer.emergency_contacts.max.count` (GLOBAL, INTEGER, seeded by `V14`), same mechanism as saved locations. This limit matters more in Phase 10 (SOS fan-out) than Phase 4, but the cap belongs here where the contacts are created.

## 5. New error code

`ErrorCode.LIMIT_EXCEEDED` (`422 UNPROCESSABLE_ENTITY`, default message *"A configured limit has been reached."*) — generic across saved locations and emergency contacts (and reusable later) rather than minting a bespoke code per resource.

## 6. Ride history

```
GET /api/v1/customers/me/rides
```

`ROLE_CUSTOMER`, paginated (`page`, `size`, `sort` per the existing API convention), backed directly by the already-written `BookingRepository.findByCustomerIdOrderByCreatedAtDesc`. Response items are the same booking-summary shape used elsewhere — see `docs/api/booking.md` §6 for the exact DTO, defined once there rather than duplicated.

## 7. Decisions (2026-10-04)

| # | Decision |
|---|----------|
| C1 | Phone-number change deferred past Phase 4 entirely. |
| C2 | Email: optional, unverified, editable with no re-verification — contact info only, never an auth/recovery factor. |
| C3 | Profile photo upload deferred to Phase 5 (needs `FileStorage`). |
| C4 | Saved-locations limit: 10, configurable (`customer.saved_locations.max.count`). |
| C5 | Emergency contacts: `PUT` edits in place. |
| C6 | Emergency-contacts limit: 5, configurable (`customer.emergency_contacts.max.count`). |

Implementation follows; see `docs/development/roadmap.md` Phase 4 for exit criteria.
