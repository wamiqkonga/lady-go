# Lady Go — Customer Profile, Saved Locations, Emergency Contacts (Phase 4)

Status: Draft v0.1 · for approval before implementation.

Scope: `customers` module. Builds on tables already migrated in Phase 2 (`customers`, `saved_locations`, `emergency_contacts` — `V3__customers.sql`) and the entities/repositories already written there (`Customer`, `SavedLocation`, `EmergencyContact` + their repositories, including `countByCustomerId` on both child repositories — written in Phase 2 clearly anticipating the limits this document now specifies). No schema changes.

## 1. A gap this phase must close first: customer row creation

`CustomerRepository` requires a `Customer` row keyed by `user_id` (`uq_customers_user`), but `OtpLoginService.verifyOtp` (Phase 3) only ever creates a `User` row — it never creates the matching `Customer` row for a new CUSTOMER signup. Every endpoint below needs `customers.me` to resolve, so Phase 4 adds one thing to existing Phase 3 code: when `OtpLoginService` creates a new `User` with `role = CUSTOMER`, it also creates the corresponding `Customer` row (`user_id`, `default_city_id = null`, `preferences = {}`) in the same transaction. This is a small, backward-compatible addition to `OtpLoginService`, not a redesign — called out here because it's a prerequisite, not because it changes anything about Phase 3's approved behaviour.

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

`PUT` body: `{ "name": "...", "email": "...", "defaultCityId": "..." }`. `phone` is never editable through this endpoint — per `docs/product/user-flows.md` §2.6 ("Phone/email change requires OTP re-verification") and ADR-010 (phone is the login identifier), changing it is identity-sensitive and out of scope for Phase 4 (OQ-C1 below). `email` *is* editable here with no re-verification step, because there is currently no email-OTP channel to re-verify it with — only phone OTP exists (OQ-C2).

`name`: required, 1–120 chars (matches `users.name varchar(120)`). `email`: optional, validated as an email shape, lower-cased before the existing `uq_users_email` (case-insensitive) check — reject with `VALIDATION_FAILED` on conflict, not a 500 from the DB constraint.

`profilePhotoKey` is read-only in Phase 4: the field exists (maps to `users.profile_photo_key`) but there is no upload endpoint yet. Uploading a photo needs the `FileStorage` SPI (private object storage + short-lived signed URLs, per ADR-009), which `docs/architecture/backend-architecture.md` associates with driver documents starting Phase 5. Pulling it forward into Phase 4 just for a profile photo is a scope call for you (OQ-C3) — the default below is to defer it.

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

**Limit**: a new `configuration` key `customer.saved_locations.max.count` (GLOBAL, INTEGER), read through `ConfigurationService.getInt(...)`, checked via the already-written `SavedLocationRepository.countByCustomerId` before insert. On the limit being hit: `422` with a generic `LIMIT_EXCEEDED` error code (new — see §5) naming the resource and the configured maximum in the message, e.g. *"You can save up to 10 locations."* Proposed default **10** (OQ-C4 — nothing in any existing doc sets this number).

## 4. Emergency contacts

```
GET    /api/v1/customers/me/emergency-contacts
POST   /api/v1/customers/me/emergency-contacts
DELETE /api/v1/customers/me/emergency-contacts/{id}
```

No `PUT` — an emergency contact is small enough that changing it is delete-and-recreate, keeping the API surface smaller (OQ-C5 if you'd rather have update-in-place).

```json
{ "id": "...", "name": "...", "phone": "+91...", "relationship": "...", "createdAt": "..." }
```

`phone`: required, same E.164 pattern as `users.phone` (schema already enforces it via CHECK; validate client-side too for a fast error). `relationship`: optional free text, ≤50 chars. `uq_emergency_contacts_phone (customer_id, phone)` means the same number can't be added twice for one customer — surfaced as `409` `RESOURCE_NOT_FOUND`... no: as a `VALIDATION_FAILED` with a clear message, not a raw constraint-violation 500.

**Limit**: `customer.emergency_contacts.max.count` (GLOBAL, INTEGER), same mechanism as saved locations. Requirements only ever mention "emergency contact" in the singular (`docs/product/requirements.md` FR-CUS-1), but the schema allows many and SOS (`docs/product/user-flows.md` §2.5) says "emergency contact notified" without saying only one is notified — proposed default **3** (OQ-C6), enough for a realistic safety setup without being unbounded. This limit matters more in Phase 10 (SOS fan-out) than Phase 4, but the cap belongs here where the contacts are created.

## 5. New error code

`ErrorCode.LIMIT_EXCEEDED` (`422 UNPROCESSABLE_ENTITY`, default message *"A configured limit has been reached."*) — generic across saved locations and emergency contacts (and reusable later) rather than minting a bespoke code per resource.

## 6. Ride history

```
GET /api/v1/customers/me/rides
```

`ROLE_CUSTOMER`, paginated (`page`, `size`, `sort` per the existing API convention), backed directly by the already-written `BookingRepository.findByCustomerIdOrderByCreatedAtDesc`. Response items are the same booking-summary shape used elsewhere — see `docs/api/booking.md` §6 for the exact DTO, defined once there rather than duplicated.

## 7. Open questions

| # | Question | Default if unanswered |
|---|----------|------------------------|
| OQ-C1 | Should Phase 4 support changing phone number at all (full re-verification via OTP on the new number, with the old number freed only after success)? | Not supported in Phase 4; phone is fixed after signup until a later phase decides this deliberately |
| OQ-C2 | Email change with no re-verification (no email-OTP channel exists) — acceptable for MVP, or should email changes be blocked entirely until an email-verification channel exists? | Allowed, unverified (matches `users.email` having no `email_verified_at` column today) |
| OQ-C3 | Pull `FileStorage` (ADR-009) forward into Phase 4 for profile photo upload, or defer to whenever it's first built for driver documents? | Defer; `profilePhotoKey` stays read-only/null until then |
| OQ-C4 | Saved locations limit — is 10 right? | 10, via `customer.saved_locations.max.count` |
| OQ-C5 | Emergency contacts: add `PUT` for update-in-place, or is delete-and-recreate fine? | Delete-and-recreate only |
| OQ-C6 | Emergency contacts limit — is 3 right? | 3, via `customer.emergency_contacts.max.count` |

No code has been written for this phase yet.
