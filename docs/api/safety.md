# Safety: SOS + trip sharing — design (Phase 7.5)

Status, updated per decision: **trip sharing and a one-tap "Call 112" button are in scope for the
closed pilot; SOS (§1–§3, §7's SOS-specific parts) is deferred past the pilot, gated as a hard
requirement before any public launch** (`docs/development/roadmap.md`'s new "Launch gates" section is
the tracked trigger for this, same shape as the existing security-backlog table there). "Call 112" is
a plain native-dialer button in the client apps - no backend involvement, no platform liability framing
of an auto-dial; it's listed here only because the client requirement belongs next to the rest of this
doc's safety-affordance decisions, not because it needs any API. Trip sharing's implementation is
tracked in a dedicated follow-up; this document remains its design of record. Per
`docs/development/roadmap.md`'s decided reordering, this was the phase immediately after ride lifecycle
(6.5), still ahead of Payments (8) - the SOS deferral doesn't change that ordering, only SOS's own
scope within it.

## 0. What already exists — this is an API/service design on top of existing schema, not a new one

Phase 2 (`V10__safety_support.sql`) already created the core tables, and `docs/product/requirements.md`
already states `FR-SAF-1: SOS creates a safety incident, notifies admin and emergency contact (MVP)`.
Nothing in this phase needs to re-decide the data model; the job here is the service/API layer and the
decisions requirements.md explicitly left open (D9: "Admin queue + emergency contact SMS; ops procedure
to be defined").

- **`safety_incidents`**: `ride_id` (NOT NULL today — see §1's open question), `reporter_id`, `type`
  (`SOS`, `REPORT_DRIVER`, `REPORT_CUSTOMER`, `OTHER`), `severity` (`LOW`/`MEDIUM`/`HIGH`/`CRITICAL`,
  default `MEDIUM`), `status` (`OPEN`/`ACKNOWLEDGED`/`ESCALATED`/`RESOLVED`), `location`, `notes`,
  `acknowledged_by`/`acknowledged_at`, `resolved_by`/`resolved_at`, `legal_hold`. No service has ever
  written to this table — `SafetyIncident`/`SafetyIncidentEvent` entities and their repositories exist,
  unused.
- **`safety_incident_events`**: append-only (`prevent_update` trigger already enforces this at the DB
  level), `incident_id`, `actor_id`, `event_type` (free-form `varchar`, not an enum — whatever this
  phase's service writes), `notes`, `metadata jsonb`.
- **`trip_shares`**: `ride_id`, `created_by`, `token_hash` (the raw token is never stored — same
  principle as `refresh_tokens`/OTP hashing elsewhere in this codebase), `expires_at`, `revoked_at`.
- **`emergency_contacts`**: `customer_id`, `name`, `phone`, `relationship` — already has a full
  CRUD API (Phase 4, `docs/api/customer-profile.md`). Per requirements.md, an emergency contact is "not
  a registered user" — they're reached by phone (SMS), never by push notification, never have an
  account.

This phase does **not** reuse `RideFlag` (the ride-lifecycle operational-signal table from
docs/api/ride-lifecycle.md §4) — a safety incident is categorically different from "the rider ended the
ride early" or "a cash dispute": it needs its own severity/escalation lifecycle, a legally-sensitive
retention hook (`legal_hold`), and a timeline, all of which `safety_incidents` already has and `RideFlag`
deliberately doesn't.

## 1. SOS trigger

```
POST /api/v1/customers/me/sos   { latitude, longitude, notes? }
POST /api/v1/drivers/me/sos     { latitude, longitude, notes? }
```

Both create a `SafetyIncident(type=SOS, severity=CRITICAL, status=OPEN)`, `reporter_id` = the caller's
own user id, `location` = the coordinates in the request (client-supplied — see §6 for why this is
intentionally trusted here, unlike the location-spoofing-defended driver-location-update path).

**Open question - `ride_id NOT NULL`**: the column as it exists today requires a ride. `rides` rows
only exist from `DRIVER_ACCEPTED` onward (`RideStatusSyncListener`, docs/api/matching.md). That means a
rider who feels unsafe while still `SEARCHING_DRIVER` or with a driver only `DRIVER_ASSIGNED` (not yet
accepted) has **no incident to attach to** under the current schema - exactly the moment an SOS feature
exists for. Proposing a migration: make `ride_id` nullable, add `booking_id` (nullable too - a driver or
rider might also want to raise one with no active booking at all, e.g. after being dropped off).
**Flagging this as a schema change needing your approval before any code**, not deciding it silently.

A `safety_incident_events` row is appended immediately (`event_type = 'CREATED'`), recording the raw
request (coordinates, notes) in `metadata`.

## 2. Who gets alerted, and how

Per D9 (already decided): **admin queue + emergency contact SMS**; "optional authorities" is this
doc's own open question, not re-litigating D9.

- **Admin**: the incident's existence in `safety_incidents` at `status = OPEN` IS the queue (the
  roadmap's own Phase 9 "SOS/incident queue" admin screen reads this table directly - no separate queue
  structure needed). Additionally, `RealtimePushService.pushToUser` (already built, docs/api/matching.md
  §7) pushes to every currently-connected ADMIN session the moment the incident is created, `/queue/sos`,
  so a logged-in admin sees it without polling. The DB row is still the system of record either way -
  same "push is best-effort, the row is truth" principle as every other realtime feature in this
  codebase.
- **Emergency contact(s)**: for a rider-reported incident, every row in that customer's
  `emergency_contacts` gets an SMS via the existing `SmsService` interface (currently `MockSmsService`
  in dev/test, same provider-abstraction gap already tracked for OTP - D3). A new message template
  (not the OTP one), something like: "`{name}` may need help on a Lady Go ride. Location:
  `{maps-link}`. This is an automated alert." For a DRIVER-reported incident: drivers have no
  `emergency_contacts` of their own in the current schema (that table is `customers`-scoped only) -
  **open question**: add driver emergency contacts too (a real schema gap, visible now that drivers can
  also trigger SOS), or is a driver's SOS admin-only for now?
- **Authorities** (police/ambulance): **explicitly out of scope for this phase, flagged as your open
  question per D9** - this needs a real operational decision (which jurisdiction's emergency number,
  whether the platform ever calls on the user's behalf vs. just surfacing the information to the admin
  to decide, liability implications of a wrong or delayed auto-dial). Proposing: nothing automated here
  yet; the admin, once alerted, is the one who decides whether to involve authorities, using whatever
  the ops runbook (still to be written, per the roadmap's own Phase 1 safety backlog item) says.

## 3. Driver/rider acknowledgement vs. admin acknowledgement

`status: OPEN → ACKNOWLEDGED` is admin-only (`acknowledged_by`/`acknowledged_at`), via
`POST /api/v1/admin/safety-incidents/{id}/acknowledge`. Separately, once alerted, the OTHER party on the
ride (if the incident was raised by one side) should probably be told something is being looked at -
**open question**: does the non-reporting party get any visibility into an SOS being active on their
own ride at all, or does that defeat the point (if a driver is the threat, telling them an SOS was
raised against them could escalate, not de-escalate)? Proposing: **no visibility to the other party by
default** - only admin and the reporter's own emergency contacts know. Flagging for your confirmation,
since getting this wrong in either direction is a real safety (not just UX) decision.

`POST /api/v1/admin/safety-incidents/{id}/escalate` and `.../resolve` (with a required `notes` body for
resolve) round out the admin side, each appending a `safety_incident_events` row (`ESCALATED`,
`RESOLVED`) and, for `resolve`, setting `legal_hold` per §5 below.

## 4. Trip sharing — **implemented**

```
POST   /api/v1/bookings/{bookingId}/trip-shares              -> { id, token, expiresAt }
DELETE /api/v1/bookings/{bookingId}/trip-shares/{shareId}     (revoke)
GET    /api/v1/trip-shares/lookup                             -- PUBLIC, unauthenticated
       header: X-Trip-Share-Token: <token>
```

Keyed by `bookingId`, not `rideId` as originally drafted - resolved to the underlying `Ride`
internally, for API consistency with every other customer-facing ride-lifecycle endpoint
(docs/api/ride-lifecycle.md), all of which are booking-id-keyed. `trip_shares.ride_id` is still what
the schema stores.

The public lookup token travels in a request header (`X-Trip-Share-Token`), never a URL path/query
parameter - a typical access log captures the request line (method + path + query string) but not
headers, so this keeps the raw secret out of that log surface. `Cache-Control: no-store` on every
response. A missing header is rejected with the identical generic error as every other failure case
(§4's "identical error" requirement) - not a distinct 400.

- **Token**: a cryptographically random 256-bit value (`SecureRandom`, base64url-encoded for the
  shareable form - not HMAC-derived like the ride PIN, deliberately: a leaked/guessable trip-share link
  is a much bigger exposure than a 4-digit PIN exchanged in person, so this needs real entropy, not a
  small keyspace). Only `SHA-256(token)` hex is stored in `token_hash`; the raw token appears exactly
  once, in the creation response, same principle as `refresh_tokens`. The lookup's hash comparison uses
  `MessageDigest.isEqual` (constant-time) rather than a plain string/array equality, in addition to the
  DB-side indexed exact-match lookup.
- **Expiry**: two layers. `trip_share.default_ttl.hours` (12) is a creation-time safety ceiling,
  independent of when the ride actually ends (a share created early, right after booking, shouldn't
  expire just because the ride itself hasn't started yet). Once the trip is over,
  `trip_share.post_ride_grace.minutes` (30) governs the real cutoff instead - computed at lookup time
  by joining to the `bookings`/`rides` tables, not written back onto `trip_shares` at ride completion
  (keeps ride-lifecycle code from needing to know trip-sharing exists at all). **Security review
  finding, CRITICAL**: "trip is over" is keyed off the **booking's** status (not in
  `{DRIVER_ACCEPTED, DRIVER_ARRIVING, DRIVER_ARRIVED, RIDE_STARTED}`), not the `Ride` row's own
  `status` column - nothing in this codebase ever sets `Ride.status` to `CANCELLED` (every real cancel
  path only moves `BookingStatus`), so a ride-status-only check never caught a cancelled booking at
  all, leaking the driver's ongoing, continuously-updated live location (by then quite possibly serving
  a completely different rider) for up to the full 12h ceiling instead of ~30 minutes. Once over, the
  location shown is the ride's own frozen `endLocation` (null if the trip never properly started, e.g.
  an early cancellation), never the driver's current position again.
- **Revocable**: `revoked_at` set by the creator (the rider) at any time; the public endpoint checks
  `revoked_at IS NULL` and both expiry layers above, failing the exact same generic way for all of them.
- **Active-link cap**: `trip_share.max_active_per_ride` (3) - counts non-revoked, non-expired shares
  for the ride; revoking one frees a slot.
- **What a share link shows**: implemented as its own narrow DTO (`TripShareLookupResponse`), not a
  view of `BookingDetailResponse` at all - driver first name (never the full name - split off the first
  token of `User.name`), vehicle make/model/color/registration, and the driver's live/last-known
  location point + its server-receive timestamp. No rider details, no phone numbers, nothing else.
- **Rate limiting the public endpoint**: two independent `RateLimiter` buckets -
  `trip_share.lookup.rate.max.attempts` (10 per 60s) keyed by the token's hash (never the raw token, to
  keep the rate limiter's own storage from becoming a second place the secret could end up), and
  `trip_share.lookup.ip.rate.max.attempts` (30 per 60s) keyed by the resolved client IP
  (`ClientIpResolver`, already built for exactly this), which catches guessing spread across many
  different tokens from one source.

## 5. `legal_hold` interplay with retention — a real gap, not yet wired to anything

`docs/database/database-design.md` §6 (still marked "proposal, needs confirmation") says
`location_history` retention is "90 days, longer for rides tied to open/closed safety incidents under
legal hold" - but **no retention/purge job for `location_history` exists in this codebase at all**
(confirmed by reading the source: `RetentionPurgeJob` only handles driver documents and ride selfies,
nothing touches `location_history`). `legal_hold` on `safety_incidents` is schema that's ready for a
purge job that hasn't been written yet.

For THIS phase: set `legal_hold = true` automatically whenever an incident's `severity` is
`HIGH`/`CRITICAL`, or whenever an admin manually flags it, and set it on `resolve` only if the admin
explicitly didn't request a hold release (default to KEEPING the hold on resolve, since resolving an
incident isn't the same as clearing its legal relevance - a resolved SOS can still matter for a later
dispute or investigation). **Not** building the actual location_history retention job in this phase
(that's its own piece of work, blocking on the retention policy doc's "needs confirmation" being
resolved first) - this phase's job is only to make sure `legal_hold` gets set correctly so that FUTURE
job has correct data to act on from day one.

## 6. Why client-supplied SOS coordinates are trusted (unlike driver location updates)

Worth stating explicitly since docs/api/matching.md §3 and docs/api/ride-lifecycle.md §6 both go to
real lengths to NOT trust a client-supplied location for pricing/matching. SOS is different: the
person raising it is reporting on THEMSELVES, in a moment that by definition can't wait for a
server-side corroboration step, and a false SOS costs the reporter (admin attention, a false-alarm mark
- see §7) more than it could ever gain them. There's no economic incentive to spoof an SOS location the
way there is to spoof a fare-relevant one. The coordinates are still just a snapshot, independent of
`drivers.current_location` - admin review can and should cross-reference both.

## 7. Abuse and false-alarm handling

- **Repeated SOS from the same reporter**: rate-limit the endpoint itself (`sos.rate.max.attempts`
  /`sos.rate.window.minutes`, proposing 3 per 15 minutes - generous, since a real emergency might need
  more than one attempt if the first silently failed) so a malfunctioning client or a deliberate
  flood can't spam the admin queue. This is NOT the same thing as penalizing false alarms - never block
  or delay the SOS action itself; the rate limit only matters for how many ROWS get created, and the
  last one always still goes through (same "the Nth attempt is what matters, not a hard wall" shape as
  the ride PIN lockout).
- **False-alarm marking**: `resolve` gets an optional `falseAlarm: boolean` alongside the required
  `notes`. **Open question - consequence**: does a false alarm have any cost to the reporter (e.g. a
  cap like the eligibility-cancel/safety-cancel caps elsewhere in this codebase), or is it tracked
  purely for admin visibility with zero automated consequence? Proposing the latter (track, never
  auto-penalize) since the cost of ever discouraging a real SOS by making the reporter fear a penalty
  is categorically worse than tolerating some false alarms - but flagging this explicitly since it's a
  real policy call, not a technical one.
- **`REPORT_DRIVER`/`REPORT_CUSTOMER`/`OTHER` incident types**: already modeled by the existing schema
  (not new to this phase) but worth noting they share the same abuse surface as the pre-existing
  `EligibilityReport` flow (docs/api/driver-onboarding.md §5) - **open question**: is `SafetyIncident`
  meant to fully replace `EligibilityReport` going forward, or do they coexist for different severities
  (eligibility concern = policy/fit issue, safety incident = acute danger)? Flagging since the two now
  visibly overlap in purpose and this phase shouldn't silently duplicate or orphan either.

## 8. Config defaults (new keys, migration TBD pending your approval on §1's schema change)

| Key | Proposed default | Meaning |
|---|---|---|
| `sos.rate.max.attempts` | 3 | Max SOS rows created per reporter per window |
| `sos.rate.window.minutes` | 15 | Window for the above |
| `trip_share.default_ttl.hours` | 6 | Default expiry for a new trip-share link |
| `trip_share.lookup.rate.max.attempts` | 10 | Public endpoint throttle, per token |
| `trip_share.lookup.rate.window.seconds` | 60 | Window for the above |

## 9. Open questions — stop here for your decision

1. **`safety_incidents.ride_id NOT NULL`** (§1): make nullable + add `booking_id`, so SOS works before
   a ride row exists? This is the one schema change this phase actually needs - everything else reuses
   V10 as-is.
2. **Driver emergency contacts** (§2): drivers currently have none in the schema - add them (symmetry
   with customers), or is a driver's SOS admin-only for now?
3. **Authorities contact** (§2): explicitly deferred per D9 - confirming that's still the right call,
   or do you want even a manual "admin can see a one-tap dial/link to local emergency services" affordance
   in this phase (still no auto-dial, just a convenience link)?
4. **Other-party visibility during an active SOS** (§3): proposing none by default - confirm.
5. **Trip-share public field list** (§4): proposing driver first name + vehicle + live/last-known
   location + ETA - confirm or adjust.
6. **False-alarm consequence** (§7): proposing track-only, no automated penalty - confirm.
7. **`SafetyIncident` vs. `EligibilityReport`** (§7): coexist by severity, or does one subsume the
   other going forward?

Stop here for approval before any code.
