# Phase 6 — Matching, Driver Availability, Offers

Status: Design draft v0.1 · design-first, no code yet. Stop for approval before implementation,
per standing instruction.

Builds on, and makes concrete, the Phase 0 sketches in `docs/architecture/backend-architecture.md`
section 5 and `docs/architecture/realtime-architecture.md` — those were drafted before any of this
existed; this doc is where the actual decisions get made. Replaces the direct `booking.driverId`
assignment in `CrossPhaseJourneyTest` with the real endpoints below (§8).

## 0. What already exists, unused

Three things were seeded or scaffolded in earlier phases, specifically so Phase 6 would have
something to build on instead of starting from zero:

- **Config keys, seeded in V1 (Phase 0), never read by any code yet:**
  `driver.search.radius.m` = 5000, `driver.response.timeout.s` = 20, `ride.max.search.time.s` = 180,
  `matching.max.offers` = 10.
- **`DriverRepository.findNearbyOnline(cityId, lon, lat, radiusM, maxResults)`** — a working native
  PostGIS query (`ST_DWithin`/`ST_Distance` on `drivers.current_location`, which is already written to
  by nothing yet either). Its own javadoc already says: *"Matching (Phase 6) will use the Redis GEO
  index for live search; this query is the database source of truth and the fallback if Redis is
  unavailable."* That sentence is treated as the starting position for §4, not re-litigated from zero.
- **`ride_offers` table (V6), `RideOffer`/`OfferStatus` domain, `RideOfferRepository`** — schema exists,
  `uq_ride_offers_one_accepted` (at most one ACCEPTED offer per booking) and
  `uq_bookings_one_active_per_driver` (at most one committed booking per driver) are already enforced
  and already covered by a passing `DatabaseConstraintsTest`. No service or controller uses this table
  yet.
- **`DriverReverificationService.isBlockedFromGoingOnline(driverId, now)`** — written in Phase 5
  specifically for this phase to call. Nothing calls it yet.
- **`RiderSelfieDeliveryService`** — already correctly restricts the rider selfie to the assigned
  driver, only in the `DRIVER_ACCEPTED..RIDE_STARTED` window. It needs no changes; it just needs a real
  caller instead of `CrossPhaseJourneyTest`'s direct `driverId` assignment.

## 1. Scope for this phase

In scope: driver go-online/offline (with re-verification blocking), driver location reporting
(HTTP, used as matching input), the matching loop itself, offer accept/decline, and WebSocket delivery
of offers to drivers and booking-state pushes to customers.

Out of scope, staying Phase 7 per the existing package-layout doc: continuous live location
*broadcast* to the customer's map during a ride, trip-share links, admin live map. Phase 6 only needs
the driver's *current* point for matching distance, not a streamed feed to anyone else. The same
WebSocket/Redis infrastructure this phase introduces is designed to carry that later without rework.

Ranking is distance-only for v1. `ratings` and `notifications` are schema-only stubs today (no
service layer) — rating-weighted ranking and push-notification offer fallback are real Phase 6+
candidates once those modules have a service layer, not a blocker for this phase. Ranking is a strategy
interface from day one specifically so adding a second factor later doesn't touch the matching loop.

## 2. Driver availability

```
PUT /api/v1/drivers/me/availability   { status: ONLINE | OFFLINE }
```

- `OFFLINE -> ONLINE` requires `verificationStatus == APPROVED` and
  `!reverificationService.isBlockedFromGoingOnline(driverId, now)` — the latter is the Phase 5 field
  finally getting consulted. Blocked: `403` with a clear reason (`"random re-verification required"` or
  `"reported - re-verification required"` based on `reverificationReason`), not a generic error.
- `ONLINE -> OFFLINE` always allowed, EXCEPT while `availabilityStatus == ON_RIDE` (an active ride in
  progress) — same "never interrupts an active ride" rule Phase 5's design doc already stated for
  re-verification. A driver mid-ride cannot toggle themselves offline until the ride ends; `ON_RIDE` is
  set by the ride-lifecycle transitions (Phase 7), not by this endpoint.
- `BUSY` (mid-offer, not yet accepted) is a status this phase introduces and clears automatically on
  offer resolution (accept/reject/timeout) — not something the driver sets directly via this endpoint.

**Open question**: should repeatedly toggling online/offline itself be rate-limited (abuse/battery-drain
vector), or is that premature for an MVP with a small driver pool? Proposing no limit for v1, revisit if
it becomes a real problem.

## 3. Driver location reporting

```
POST /api/v1/drivers/me/location   { latitude, longitude, recordedAt }
```

HTTP only for this phase (per §1, the WebSocket location channel is Phase 7's job). Validates
lat/lon bounds and that `recordedAt` isn't absurdly stale/future (reuses the same kind of sanity check
`realtime-architecture.md` §2 already specifies for the future WS path, so Phase 7 doesn't have to
invent a second version of it). Writes `drivers.current_location`/`current_location_at` (already
columns, never written to). Only accepted while `availabilityStatus != OFFLINE`.

If Redis is adopted (§4), this same endpoint also does the `GEOADD`; Postgres stays the system of
record either way (driver's last-known point survives a Redis restart/eviction).

## 4. Nearby search: Redis GEO vs. Postgres-only — recommendation

**You explicitly asked for this choice to be laid out, not pre-decided — here it is, but the Phase 0
docs already lean one way, which the recommendation below follows.**

| | Postgres-only (`findNearbyOnline`, already working) | Redis GEO + Postgres fallback (what the Phase 0 docs describe) |
|---|---|---|
| New infra | None | Redis (not yet in `pom.xml` — currently commented `-> Phase 6/7`) |
| Nearby-search latency at MVP scale (low hundreds of online drivers per city) | Fine — GiST index, sub-ms-to-low-ms | Faster, but the margin doesn't matter yet at this scale |
| Offer "claim" (one driver, one offer at a time) | `UPDATE ride_offers ... WHERE ...` / conditional `UPDATE bookings SET driver_id WHERE driver_id IS NULL`, the same atomic-conditional-update idiom already used twice in this codebase (ride-selfie claim, idempotent booking insert) | `SET NX` with TTL = response timeout — the lock expires itself, no sweep job needed |
| Offer timeout | Needs a sweep (`@Scheduled`, same shape as `RetentionPurgeJob`) to expire `ride_offers` rows past `expires_at` and move to the next candidate | Free (TTL) |
| Consistency risk | Single system of record — none | Two systems to keep in sync (Redis is the lock, Postgres is still the booking/offer record on accept) — same dual-write shape payments/webhooks already have to handle elsewhere in this codebase |
| Needed again later anyway? | No | Yes — Phase 7's live location broadcast needs Redis pub/sub for multi-node WebSocket fan-out regardless (`realtime-architecture.md` §8). Adopting it now means introducing Redis once, not twice. |
| Test cost | Zero new Testcontainers module | New `Testcontainers redis` module (none exists yet) |

**Recommendation: Postgres-only for the matching loop itself in this phase; add Redis in the *same*
phase only for the offer-claim lock and WebSocket pub/sub, not for nearby search.** Concretely:
nearby search stays on `findNearbyOnline` (it already works, it's already the documented fallback, and
the MVP driver density doesn't need GEO yet); the offer claim uses a `@Scheduled` expiry sweep instead
of a Redis TTL (one more job exactly like `RetentionPurgeJob`, not a new concept); WebSocket needs
*some* way to fan out to driver sessions, and if this backend ever runs more than one node, that's
Redis pub/sub either way — so Redis still gets introduced this phase for delivery, just not for search.
This defers the GEO/TTL-claim complexity to whenever driver density actually justifies it, while not
pretending Phase 7 won't need Redis pub/sub anyway.

**If you'd rather do the full Redis design now** (GEO + SET NX claim) instead of this staged approach,
say so — the architecture docs already describe exactly that, and it's a smaller change at this point
than it will be once more is built on top of the Postgres-only path.

## 5. Matching loop

```
BookingService.create() already transitions REQUESTED -> SEARCHING_DRIVER (existing code, unchanged).
MatchingService.start(bookingId):
  candidates = driverRepository.findNearbyOnline(cityId, pickupLon, pickupLat, radiusM, matching.max.offers)
             (already filters ONLINE + has a location; add: vehicleType match, verificationStatus=APPROVED,
              not already offered this booking)
  for each candidate, in distance order, one at a time:
    claim: UPDATE drivers SET availability_status='BUSY' WHERE id=:driverId AND availability_status='ONLINE'
           (0 rows -> raced by another booking's search, skip to next candidate)
    create ride_offers row (OFFERED, expires_at = now + driver.response.timeout.s)
    transition booking REQUESTED/SEARCHING_DRIVER -> DRIVER_ASSIGNED (actor SYSTEM)
    push offer over WebSocket to the driver (+ the driver can also poll GET /drivers/me/offers/current
         as the HTTP fallback realtime-architecture.md §6 already calls for)
    wait for accept/reject/expiry:
      accept -> DRIVER_ACCEPTED, driver stays BUSY until ride lifecycle later sets ON_RIDE (Phase 7);
                stop the loop
      reject or expiry -> driver availability_status back to ONLINE, ride_offers row REJECTED/EXPIRED,
                booking back to SEARCHING_DRIVER, continue to next candidate
  exhausted all candidates, or ride.max.search.time.s elapsed -> CANCELLED, cancelReason="NO_DRIVER_FOUND",
         actor SYSTEM (cancelReason is free-text already, no new enum needed)
```

A `@Scheduled` sweep (matching `RetentionPurgeJob`'s shape) expires `ride_offers` rows past
`expires_at` that are still `OFFERED` and advances the booking to the next candidate — this is how a
driver who never responds (app closed, no network) doesn't block the booking forever.

## 6. Accept/decline

```
POST /api/v1/drivers/me/offers/{offerId}/accept
POST /api/v1/drivers/me/offers/{offerId}/reject   { reason }
```

- Ownership: the offer's `driverId` must match the caller (404 otherwise, same pattern as every other
  driver-scoped endpoint in this codebase — existence isn't leaked to a non-owner).
- Accept: only valid while the offer is still `OFFERED` and not past `expires_at` (a stale accept racing
  the sweep job loses cleanly, same shape as the booking idempotency race). Transitions
  `ride_offers.status -> ACCEPTED`, booking `DRIVER_ASSIGNED -> DRIVER_ACCEPTED`. This is the moment
  `RiderSelfieDeliveryService` becomes reachable — no change needed there, it already gates on exactly
  this transition.
- Reject: `ride_offers.status -> REJECTED`, driver back to `ONLINE`, matching loop continues to the next
  candidate for that booking.

## 7. WebSocket

Adopts `realtime-architecture.md` §4's destinations for the two pieces this phase needs:
`/user/queue/offers` (server -> the offered driver) and `/user/queue/booking` (server -> the booking's
customer, for `DRIVER_ASSIGNED`/`DRIVER_ACCEPTED`/etc. pushes instead of polling `GET /bookings/{id}`).
The driver-location-broadcast and admin-map destinations in that doc stay Phase 7 — not built now.
Reliability/reconnect rules (§6 of that doc: REST re-fetch on reconnect, never trust a missed message)
apply as already written; not re-specified here.

## 8. Replacing the journey test's stand-in

`CrossPhaseJourneyTest`'s one direct field-set
(`booking.setDriverId(driverId)` + manual `stateMachine.transition` calls, clearly commented as a
Phase-6 stand-in) gets replaced with: driver goes `ONLINE` via §2's endpoint, `MatchingService` assigns
and offers automatically once the booking reaches `SEARCHING_DRIVER` (no test-side trigger needed beyond
what `BookingService.create()` already does), driver accepts via §6's endpoint. The test's comment
already says exactly this is the intended extension point.

## 9. Config — defaults already seeded in V1, carried forward unchanged unless you say otherwise

| Key | Current value | Meaning |
|---|---|---|
| `driver.search.radius.m` | 5000 | Nearby-search radius |
| `driver.response.timeout.s` | 20 | How long a driver has to accept/reject before the offer expires |
| `ride.max.search.time.s` | 180 | Give up (NO_DRIVER_FOUND) after this long regardless of candidates left |
| `matching.max.offers` | 10 | Max candidates tried per booking before giving up |

No new config keys needed for the Postgres-only recommendation in §4 — the sweep interval is an infra
constant like `RetentionPurgeJob`'s, not a business value.

## 10. Open questions — stop here for your decision

1. **Redis scope (§4)**: staged (pub/sub + claim-expiry sweep now, GEO later) as recommended, or the
   full Redis design (GEO + SET NX) in one go?
2. **Driver rejects an offer repeatedly** — any cap, or unlimited rejects allowed (symmetrical question
   to the eligibility-cancel cap you just added, but for ordinary reject-and-keep-searching, which costs
   the driver nothing today)? Proposing no cap for v1 — a reject just means "not now," unlike an
   eligibility-concern cancel which costs the rider a ride and creates a report.
3. **Driver online/offline toggle rate limit** (§2) — needed now or defer?
4. **`matching.max.offers` = 10 sequential, not batched** — confirming this is still the intended
   semantic (one driver offered at a time, up to 10 tried) and not "10 drivers offered simultaneously,
   first to accept wins." The existing docs' pseudocode reads sequential; batched-simultaneous is a
   materially different (and harder to get race-free) design if that's actually wanted.
5. **Scheduled driver-go-online rate limit abuse** aside, is there any limit on how many *bookings* a
   single customer can have `SEARCHING_DRIVER` at once (double-booking across devices)? Not addressed
   here; flagging since it's adjacent.
