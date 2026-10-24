# Admin web — design (pilot scope)

Status: design draft, no code yet. Design-first per instruction; stop for approval before any code.

## 0. Why this doc, and how it relates to `docs/product/admin-flows.md`

`docs/product/admin-flows.md` is the Phase 0 full vision (three roles, live map, reports, step-up MFA
on destructive config, versioned/effective-dated pricing). This doc narrows that down to exactly the
five screens the pilot actually needs, for exactly one admin: **the founder**, personally monitoring
the pilot per `docs/product/pilot-plan.md` §6. It is the "minimum cut" of admin-flows.md, the same way
`pilot-plan.md` is the minimum cut of the full product requirements.

The five screens, as given: **driver review queue with document viewing, vehicle review, ride/flag
oversight, audit log, config**. Live map, customer management, reports, and the SUPPORT/ADMIN role
split are explicitly out of scope here - they're real admin-flows.md features, just not pilot-blocking
ones (no customers need suspending before a 30-50-rider pilot starts; the founder watching one area's
rides doesn't need marker-clustering).

**Frontend**: `frontend/admin` already exists (React 19 + Vite + react-router), with route stubs for
every screen below already in `App.tsx` (`driver-verification`, `rides`, `pricing`, `audit-logs`) plus
a disabled `Login` form and a bare `apiGet` helper with no auth wiring yet. This doc is about filling
those in, not scaffolding a new app.

## 1. Screens overview — what exists vs. what's new

| Screen | Backend | Frontend |
|---|---|---|
| 1. Driver review queue + document viewing | **Exists** (`AdminVerificationController`) | New - page is currently a placeholder |
| 2. Vehicle review | **Exists** (same controller) | New - folded into screen 1, not a separate page |
| 3. Ride/flag oversight | **New** - no admin-facing ride or `RideFlag` read endpoint exists today | New |
| 4. Audit log | **New** - `AuditLog`/`AuditLogRepository` exist and are already written to, but there is no admin-facing list/search endpoint | New |
| 5. Config | **New** - `ConfigEntry`/`ConfigEntryRepository` exist and are already read internally, but there is no admin-facing list/update endpoint | New |

Screens 1-2 are pure frontend work against an already-reviewed, already-tested backend surface.
Screens 3-5 need new (small) backend work first. All five need the admin frontend's auth wiring
(§6) built before any of them can actually be used, since right now `Login` is a disabled stub.

## 2. Screen 1 — Driver review queue + document viewing

Backend, already implemented and tested (`AdminVerificationControllerIntegrationTest`,
`AdminVerificationServiceTest`):

- `GET /api/v1/admin/driver-documents` — paginated queue of pending documents.
- `GET /api/v1/admin/driver-documents/{id}/view` — returns a short-lived signed URL to the actual
  document/selfie image (every call audited — see §4).
- `POST /api/v1/admin/driver-documents/{id}/approve` / `/reject` (reject requires a note).
- `GET /api/v1/admin/drivers/{id}/review` — the full decision bundle for one driver (per its own
  javadoc: "everything needed to decide").
- `POST /api/v1/admin/drivers/{id}/approve` / `/reject`.
- `POST /api/v1/admin/drivers/{id}/trigger-reverification`.
- `GET /api/v1/admin/eligibility-reports` + `POST /api/v1/admin/eligibility-reports/{id}/resolve` —
  peer-reported eligibility concerns (a different thing from `RideFlag` — see screen 3).

Page layout:
1. **Queue list** (`driver-documents`, paginated table): submitted-at, driver name/phone, document
   type, status. Row click opens the driver's full review bundle.
2. **Driver review panel** (`drivers/{id}/review`): driver profile, vehicle, each document with an
   inline image viewer backed by the signed `view` URL (expires quickly — don't cache the URL itself,
   re-fetch on open), approve/reject per document, then approve/reject the driver as a whole once every
   document is in order.
3. **Eligibility reports** as a secondary tab on the same page (same reviewer, same reviewing
   workflow, different trigger) rather than a separate top-level screen — not asked for as its own
   item in the five, and sharing the page avoids a nav entry for something this low-volume in a
   5-10-driver pilot.

## 3. Screen 2 — Vehicle review

Backend, already implemented: `POST /api/v1/admin/vehicles/{id}/approve` / `/reject`. Vehicle details
and photos are already part of the driver review bundle (§2) — rather than a separate top-level page,
this is the vehicle section of the same driver review panel, with its own approve/reject actions
(a driver can be approved while a vehicle swap is still pending re-review, which is why these are
separate endpoints rather than one combined action).

## 4. Screen 3 — Ride/flag oversight (new backend needed)

This is the one screen with no existing admin read path at all. Two related but distinct things need
surfacing, matching `docs/api/ride-lifecycle.md` §4's `RideFlag` design and `RideStatusSyncListener`'s
root-cause fix (both recent):

**Rides list + detail** (new):
- `GET /api/v1/admin/rides` — paginated, filterable by status, driver, customer, date range. Given the
  pilot's actual scale (5-10 drivers, 30-50 riders, 2-4 weeks), this can be a straightforward filtered
  query over `Booking`/`Ride` joined - no need for the full admin-flows.md §7 live-event-timeline
  richness yet, just enough to answer "what happened on this ride."
- `GET /api/v1/admin/rides/{bookingId}` — booking + ride detail: status, fare, timestamps, any
  associated `RideFlag`s (next item). Read-only for the pilot — see the open question below on whether
  write interventions (cancel/reassign/adjust fare, per admin-flows.md §7) are needed before pilot
  start or can wait.

**Ride flags** (new) — surfaces every `RideFlagType`: `EARLY_END`, `COMPLETION_FAR_FROM_DESTINATION`,
`PIN_LOCKOUT`, `CASH_DISPUTE`, `RIDER_SAFETY_CANCEL`, `RIDER_CONFIRMED_ARRIVAL`:
- `GET /api/v1/admin/ride-flags` — paginated, filterable by type and by whether it's been reviewed
  (see open question below — `RideFlag` currently has no admin-side "reviewed" field, only
  `riderResponse`/`riderRespondedAt`, which is the *rider's* acknowledgement/dispute of a flag raised
  about them, not an admin triage state).
- Detail view: flag type, the booking/ride/customer/driver it's against, `distanceFromExpectedM` and
  `capExceeded` where relevant, the rider's own response if any.

Page layout: a single "Oversight" page with two tabs - **Rides** (list + detail) and **Flags** (list,
sorted unreviewed-first once that field exists). `RIDER_SAFETY_CANCEL` and `CASH_DISPUTE` entries
should visually stand out (these are the two types most likely to need the founder's actual attention
during a live pilot, versus `RIDER_CONFIRMED_ARRIVAL`, which is closer to a routine log entry).

## 5. Screen 4 — Audit log (new backend needed)

`AuditLog` already captures every admin action taken via screens 1-2 (login, document/driver/vehicle
approve-reject) through `AdminAuthService` and `AdminVerificationService`. What's missing is a way to
read it back:

- `GET /api/v1/admin/audit-logs` — paginated, filterable by `actorId`, `action`, `targetType`/
  `targetId`, and a `createdAt` range. `AuditLogRepository` currently only has
  `findByActorIdOrderByCreatedAtDesc`; this needs a small set of additional derived-query or
  `Specification`-based methods to support the other filters, not new schema (the table already has
  every field admin-flows.md §11 asks for: actor, action, target, metadata, IP, timestamp).

Page: a simple filterable table, newest first. No row-level detail beyond what's already in the table
(the `metadata` JSON column renders as a collapsible raw-JSON block rather than a bespoke per-action
view — a "diff" rendering per action type is admin-flows.md-vision polish, not pilot-blocking for a
log that one person reads occasionally).

**Once screens 3 and 5 below write their own actions (flag review, config change), they must also
write an `AuditLog` row** - config changes already have a defined action name in admin-flows.md §11
(`CONFIGURATION_CHANGED`); flag-review needs a new one, e.g. `RIDE_FLAG_REVIEWED`.

## 6. Screen 5 — Config (new backend needed)

`ConfigEntry` already models everything needed (`key`, `value`, `valueType`, `scope`, `cityId`,
`description`, `updatedBy`, `updatedAt`), and `ConfigurationService` already reads it at runtime
throughout the app (location staleness, matching radii, trip-share TTLs, PIN lockout thresholds,
cancellation fees, etc. - the "config defaults" documented across `docs/api/matching.md` §9 and
`docs/api/ride-lifecycle.md`). Missing: any admin-facing way to see or change a value without a direct
database edit.

- `GET /api/v1/admin/config` — list all entries, grouped by scope (`GLOBAL`/city-scoped where
  `cityId` is set).
- `PUT /api/v1/admin/config/{key}` — update one entry's value. Validates the new value against
  `valueType` before writing (reject a non-numeric value for a `NUMBER`-typed key, etc. - the same
  class of input-boundary validation the rest of the backend already applies). Writes
  `updatedBy`/`updatedAt`, and an `AuditLog` row (`CONFIGURATION_CHANGED`, `metadata` = old value +
  new value) in the same transaction.

**Deliberately NOT building for the pilot** (flagging as a scope cut, not an oversight):
admin-flows.md §9 describes versioned, effective-dated config changes (SUPER_ADMIN-only, scheduled
rollout). For a single-founder-admin pilot this is overhead with no one else's workflow to protect
against - an immediate overwrite with a full audit trail (old value visible in the audit log, so
nothing is silently lost) is proportionate. This should be revisited before Phase 9's full admin
portal, not treated as the final design. **Flagging this simplification for your confirmation**, same
as every other open question below.

Page: list grouped by scope, inline edit per row (text input matching `valueType`, with the
description shown as help text), save per row.

## 7. Auth for the pilot admin frontend

The backend side (`AdminAuthService`, email+password+TOTP, `AdminBootstrapRunner`) is already built
and unaffected by this doc. What's missing is entirely frontend: `Login.tsx` is a disabled stub and
`api.ts` has no token storage/attachment or refresh handling at all. For the pilot (one admin, the
founder):
- Wire `Login` to `POST /api/v1/auth/admin/login`, including the TOTP step.
- Store the access token in memory (not `localStorage` - same reasoning as the mobile apps'
  token-handling, avoiding XSS-exfiltrable persistent storage) with refresh-token rotation handled the
  same way the customer/driver apps already do it.
- `apiGet`/a new `apiPost`/`apiPut` attach `Authorization: Bearer` and surface a 401 by redirecting to
  `/login`, clearing the in-memory token.

No role-based UI hiding is needed for the pilot (single `SUPER_ADMIN` founder account) - the
admin-flows.md §1 role matrix (SUPPORT vs ADMIN vs SUPER_ADMIN) is a post-pilot concern once there's
more than one admin user.

## 8. Open questions — stop here for your decision

1. **`RideFlag` admin-review state** (§4): add a nullable `adminReviewedAt`/`adminReviewedBy` pair (a
   small migration) so the flags list can show reviewed-vs-not, separate from the rider's own
   `riderResponse`? Or is read-only viewing enough for a founder who's watching the admin console
   continuously anyway during pilot hours?
2. **Ride detail: read-only, or does the founder need write interventions** (cancel/reassign/adjust
   fare, per admin-flows.md §7) **before pilot start?** Proposing read-only for v1 — the founder can
   reach any pilot participant by phone directly (per pilot-plan.md §6), making an in-console
   intervention action a convenience rather than a necessity for a 5-10-driver cohort.
3. **Config versioning/effective-dating** (§6) — confirm the proposed cut (immediate overwrite +
   audit trail, no versioning) is acceptable for pilot scope, deferred to Phase 9.
4. **Eligibility reports as a tab on the driver-review page, not a separate screen** (§2) — confirm,
   or should it be its own nav item after all?

Stop here for approval before any code.

## 9. Screens 3-5 — decisions actually built (resolves §8's open questions 1-3)

Built directly off the explicit instruction that superseded the open questions above, rather than
waiting on them further:

- **Open question 1, resolved yes**: `RideFlag` gained `adminReviewedAt`/`adminReviewedBy` (already
  existed) plus a new `adminReviewNote` column (`V26`). Review now *requires* a non-blank note
  (`AdminRideFlagReviewRequest`), stored and written into the same `AuditLog` row
  (`RIDE_FLAG_REVIEWED`) - an unreviewed-vs-reviewed flags list was not enough on its own; the
  instruction asks for *why* a flag was closed, not just that it was.
- **Open question 2, unchanged**: ride detail stays read-only. It now additionally surfaces masked
  customer/driver contacts (`AdminPersonRefResponse` - name + last-4-digits phone, same masking
  convention as the existing driver-document screen), `cashConfirmedAt`/`cashDisputedAt`, and the
  `BookingEvent` timeline (`AdminBookingEventResponse`). Pickup address is included by routing through
  `BookingVisibilityPolicy.isVisible(ADMIN, status, PICKUP_ADDRESS_EXACT)` rather than hardcoding it -
  that policy currently grants `ADMIN` every field in every status, so the visible behaviour is
  unchanged, but a future tightening of the policy now automatically reaches this screen instead of
  needing a second, separately-maintained "admin sees everything" rule here. Rider live location and
  trip-share tokens are never read onto any admin DTO - there is no field for them to flow through.
- **Open question 3, resolved yes**: config stays immediate-overwrite with no versioning. Added:
  `minValue`/`maxValue` on `AdminConfigEntryResponse`, sourced from `AdminConfigService`'s existing
  `INTEGER_BOUNDS`/`DECIMAL_BOUNDS` tables (not a new, separately-maintained bound - the same bound the
  write path already enforces), and a required `reason` on every write, audited alongside old/new
  value. SUPER_ADMIN-only write was already true; the frontend additionally removes the edit control
  entirely for a plain ADMIN (cosmetic only - `@PreAuthorize("hasRole('SUPER_ADMIN')")` on the
  controller remains the actual enforcement).
- **§7's "no role-based UI hiding is needed for the pilot" is superseded** for these three screens
  specifically: config write is now ADMIN-vs-SUPER_ADMIN-visible, so the frontend hides what a role
  cannot do. This does not change screens 1-2 (still single-role pilot auth as written).
- **Audit-log screen (§5)**: backend was already complete and unaffected by this round of changes.
  Confirmed by re-reading every `AuditLog`/`BookingEvent` `metadata` write site in the codebase
  end-to-end: none ever puts a secret, token, OTP, or full phone number into a `metadata` map (fee/
  currency, config old/new value + reason, flag type + note, boolean flags only) - the "never render
  one of those" requirement holds structurally, not just because the viewer renders text-only.
