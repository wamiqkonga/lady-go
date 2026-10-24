# Lady Go — Fare Estimate, Booking, Booking State Machine (Phase 4)

Status: v0.2 · **Approved 2026-10-04** (decisions B1–B4 below). Implementation proceeds against this version.

Scope: `bookings` and `pricing` modules. Builds on tables already migrated in Phase 2 (`bookings`, `booking_events`, `ride_offers`, `pricing_rules` — `V5__pricing.sql`, `V6__bookings_events_offers.sql`) and the entities/repositories already written there, including `BookingRepository.findByCustomerIdOrderByCreatedAtDesc` (ride history, already built) and `PricingRuleRepository.findActive` (already built, exactly the lookup fare calculation needs). Schema changes in this version: `V13` adds `bookings.idempotency_key` (B3); no other schema changes.

**Explicit scope boundary**: this phase delivers booking creation, the state machine, cancellation, and history. It does **not** deliver driver matching (Phase 6) — there is no `MatchingService` yet, no driver search, no offers sent. A Phase 4 booking reaches `SEARCHING_DRIVER` and stays there until Phase 6 ships a listener that actually looks for a driver. This is intentional, not an oversight (§4).

## 1. Fare estimate

```
POST /api/v1/bookings/estimate
```

`ROLE_CUSTOMER`. Request:

```json
{
  "pickupLatitude": 34.08, "pickupLongitude": 74.80,
  "destinationLatitude": 34.13, "destinationLongitude": 74.87,
  "vehicleType": "CAB"
}
```

1. Resolve the serving city from the **pickup point**, not the customer's `default_city_id` — a customer travelling outside their home city should still get a real estimate for where they actually are. `SELECT id FROM cities WHERE active AND ST_Contains(service_area, :pickupPoint)`. If no city's `service_area` contains the point: `422` `OUT_OF_SERVICE_AREA` (new error code, §8).
2. `MapsService.calculateRoute(pickup, destination)` for real distance/duration. **B1/M3**: if the Maps provider is unavailable, this degrades to `calculateDistanceMeters` (straight-line) + an assumed average speed, and the response is marked `"approximate": true` (`docs/api/maps-service.md` §5) — this endpoint never hard-fails just because the provider is briefly down.
3. `ride.max.distance.m` (already seeded, Phase 2) caps trip distance — over the limit is `422` `VALIDATION_FAILED`.
4. `PricingRuleRepository.findActive(cityId, vehicleType, now)` — `404` `RESOURCE_NOT_FOUND` if no rule is configured for that city+vehicle type yet.
5. `FareCalculator.estimate(rule, distanceM, durationS)`:

```
distanceFare = rule.perKm * (distanceM / 1000.0)
timeFare     = rule.perMinute * (durationS / 60.0)
subtotal     = rule.baseFare + distanceFare + timeFare
serviceFee   = subtotal * (rule.serviceFeePct / 100) + rule.serviceFeeFlat
total        = max(rule.minimumFare, subtotal) + serviceFee
```

All money in minor units (ADR-007), rounded to the nearest minor unit at each step.

Response — **B1, decided: breakdown *and* total**:

```json
{
  "cityId": "...", "vehicleType": "CAB", "currency": "INR",
  "distanceMeters": 7400, "durationSeconds": 1140, "approximate": false,
  "estimatedFare": 18500,
  "breakdown": { "baseFare": 5000, "distanceFare": 8880, "timeFare": 3800, "serviceFee": 820 },
  "pricingRuleId": "..."
}
```

## 2. Create a booking

```
POST /api/v1/bookings
Idempotency-Key: <client-generated UUID>
```

`ROLE_CUSTOMER`. Request carries what the customer chose **plus the fare they last saw**, used only as a staleness check, never as the number that gets charged:

```json
{
  "pickupLatitude": 34.08, "pickupLongitude": 74.80, "pickupAddress": "...",
  "destinationLatitude": 34.13, "destinationLongitude": 74.87, "destinationAddress": "...",
  "vehicleType": "CAB", "paymentMethod": "CASH", "scheduledAt": null,
  "expectedFare": 18500
}
```

**B2, decided**: the server always recomputes the fare from scratch — fresh city resolution, fresh `MapsService.calculateRoute` call (no fallback here — see §1 vs. `docs/api/maps-service.md` §5: this endpoint **fails closed** with `503 MAPS_UNAVAILABLE` on a Maps outage, it does not approximate a real booking), fresh active `PricingRule` lookup, fresh `FareCalculator` run. The client's `expectedFare` is never trusted as the charged amount — it is compared against the freshly-computed total:

```
delta = abs(freshFare - expectedFare) / expectedFare
if delta > booking.fare.change.tolerance.pct / 100:
    reject 422 FARE_CHANGED, body = the fresh estimate (same shape as section 1's response)
else:
    proceed, persist the FRESH fare (not expectedFare)
```

`booking.fare.change.tolerance.pct` is a new `configuration` key (GLOBAL, DECIMAL, seeded by `V14`) — proposed default **5** (5%), loose enough to absorb normal traffic-condition drift between the `/estimate` call and booking creation a few seconds later, tight enough to catch a genuinely stale quote (pricing rule changed, route recalculated very differently) before the customer is surprised by the charged fare. On `FARE_CHANGED`, the client is expected to show the new quote and let the customer re-confirm with a fresh `POST /bookings` carrying the new `expectedFare` — no auto-retry loop, since that would just be the client silently accepting whatever price the server names.

`scheduledAt`: accepted and persisted (the column exists), but Phase 4 does not act on it differently from an immediate booking — scheduled-ride handling is explicitly post-MVP (`docs/product/requirements.md` §4.2).

**B3, decided**: `Idempotency-Key` is required and durable. `V13` adds `bookings.idempotency_key varchar(100) NOT NULL` plus `UNIQUE (customer_id, idempotency_key)`. First request with a given key creates the booking (`201`); a repeat with the same key and customer returns the *original* booking (`200`, not `201`, and does not recompute or re-check the fare — it's the same request, not a new one). Concurrent identical requests racing past the initial lookup are handled by catching the unique-constraint violation on insert and re-reading the winning row, rather than trusting a check-then-act lookup to be race-free.

On success: insert `bookings` row at `REQUESTED`, write the first `booking_events` row (`from_status = NULL`, `to_status = 'REQUESTED'`, `actor_type = 'CUSTOMER'`), then immediately run the state machine's `REQUESTED → SEARCHING_DRIVER` transition (§4) in the same transaction and write the second `booking_events` row (`actor_type = 'SYSTEM'`, `reason = 'awaiting_matching'`). Response is the booking detail shape (§6) at `SEARCHING_DRIVER`, plus the ride PIN (§3).

Eligibility (D5) is checked here, through the placeholder interface — §7.

## 3. The ride PIN — B4, decided: derived, not stored

**No cache, no extra storage of any kind.** The PIN is computed on demand:

```
pin = HMAC-SHA256(LADYGO_RIDE_PIN_SECRET, bookingId.toString())  →  take as an unsigned integer  →  mod 10000  →  zero-pad to 4 digits
```

Deterministic and stateless: given the same `bookingId` and the same server secret, the same 4-digit PIN comes out every time, so it can be recomputed on every request that needs it — nothing to cache, nothing to expire, nothing to lose on a restart. `bookings.ride_pin_hash` (the Phase 2 column) is **not populated** by this design — it becomes unused going forward; it is not dropped (that would be a schema change this phase wasn't asked to make), just left `NULL`. This is called out explicitly rather than left as a silent oddity for whoever reads the schema next.

`GET /bookings/{id}` includes `ridePin` (the derived value) whenever the caller is the booking's own customer and status is before `RIDE_STARTED` — always available, not time-limited by a cache TTL, for as long as the booking itself exists in that state.

**Verification, rate-limited and lockable per ride** (for Phase 5's driver-side "enter the PIN" flow to call — no HTTP endpoint ships in Phase 4, since ride-start is Phase 5's ride-lifecycle scope per the roadmap, but the service method is built and tested now):

```java
public boolean verify(UUID bookingId, String candidatePin) {
    if (!rateLimiter.tryConsume("ride_pin:" + bookingId, maxAttempts, lockoutWindow)) {
        throw new RateLimitExceededException();   // locked for this ride, not globally
    }
    return deriveDisplayPin(bookingId).equals(candidatePin);
}
```

Reuses Phase 3's `RateLimiter`/`DatabaseRateLimiter` (`docs/security/auth-design.md` §8) rather than inventing a second rate-limiting mechanism — one bucket per `bookingId`, so a brute-force attempt against one ride's 10,000 possible PINs is bounded the same way a login brute-force is, and doesn't touch any other ride's budget. `ride.pin.max.attempts` and `ride.pin.lockout.window.minutes` are new `configuration` keys (seeded by `V14`) — proposed defaults **5 attempts / 15 minutes**, matching the admin-login lockout shape already established.

New secret: `LADYGO_RIDE_PIN_SECRET` (env only, ≥32 bytes, never committed — same treatment as every Phase 3 secret; added to the secrets list in `docs/security/auth-design.md` §10 and the local runbook).

## 4. Booking state machine

One `BookingStateMachine` service; no controller or other service ever sets `bookings.status` directly (`docs/architecture/backend-architecture.md` §4). The exact graph is already specified in `docs/product/user-flows.md` §1 and the `bookings_status_check` CHECK constraint already encodes every valid status value — this just turns that graph into an explicit transition table:

```java
private static final Map<BookingStatus, Set<BookingStatus>> ALLOWED = Map.of(
    REQUESTED,        Set.of(SEARCHING_DRIVER, CANCELLED),
    SEARCHING_DRIVER, Set.of(DRIVER_ASSIGNED, CANCELLED),
    DRIVER_ASSIGNED,  Set.of(DRIVER_ACCEPTED, SEARCHING_DRIVER, CANCELLED),
    DRIVER_ACCEPTED,  Set.of(DRIVER_ARRIVING, SEARCHING_DRIVER, CANCELLED),
    DRIVER_ARRIVING,  Set.of(DRIVER_ARRIVED, SEARCHING_DRIVER, CANCELLED),
    DRIVER_ARRIVED,   Set.of(RIDE_STARTED, SEARCHING_DRIVER, CANCELLED),
    RIDE_STARTED,     Set.of(RIDE_COMPLETED),             // no CANCELLED - see below
    RIDE_COMPLETED,   Set.of(PAYMENT_PENDING),
    PAYMENT_PENDING,  Set.of(COMPLETED),
    COMPLETED,        Set.of(),
    CANCELLED,        Set.of()
);
```

`transition(bookingId, to, actor, reason)`:
1. Load the `Booking` (optimistic lock via the existing `@Version` column — a concurrent transition attempt fails fast with a clear conflict rather than silently clobbering).
2. `to` must be in `ALLOWED.get(booking.status)`, else `BookingStateException` (`409`, `BOOKING_INVALID_STATE` — error code already exists, nothing new needed).
3. Authorise the actor against the transition: a `CUSTOMER` may only ever *initiate* a transition to `CANCELLED` (from an allowed pre-`RIDE_STARTED` state) — every other transition is driven by `SYSTEM` (matching, Phase 6) or `DRIVER`/`ADMIN` in later phases. Phase 4 only ever exercises `REQUESTED→SEARCHING_DRIVER` (system) and `*→CANCELLED` (customer); the rest of the table exists now so Phase 5/6/8 don't need to touch this service.
4. Persist the new status, write one `booking_events` row (append-only, already enforced by a DB trigger — `prevent_update`), publish a `BookingStatusChangedEvent` (Spring `ApplicationEvent`, in-process; nothing subscribes to it yet in Phase 4, and `MatchingService` in Phase 6 is the first real subscriber).

**Why `RIDE_STARTED` has no `CANCELLED` edge**: `docs/product/user-flows.md` §1 is explicit — "After `RIDE_STARTED` a customer cannot cancel; they can only end the ride via the support/safety path." Phase 4 enforces this by simply not having the edge in the table.

## 5. Cancellation

```
POST /api/v1/bookings/{id}/cancel
```

`ROLE_CUSTOMER`, body `{ "reason": "..." }` (free text, required). Calls `BookingStateMachine.transition(id, CANCELLED, CUSTOMER, reason)`. Fee logic (D7 default: "free grace window, then flat fee"):

- `REQUESTED` / `SEARCHING_DRIVER`: always free (no driver committed yet).
- `DRIVER_ASSIGNED` through `DRIVER_ARRIVED`: free if cancelled within `cancellation.grace.seconds` (already seeded, Phase 2) of the `DRIVER_ACCEPTED` transition timestamp (read from that `booking_events` row); otherwise the active `pricing_rules.cancellation_fee` for the booking's city+vehicle type applies.

There is no column on `bookings` for an assessed-but-not-yet-charged cancellation fee. The assessed fee is recorded in the `CANCELLED` `booking_events` row's `metadata` jsonb (`{"cancellationFee": 5000, "currency": "INR"}`); Phase 8 (Payments) reads it from there when it actually charges anything. Phase 4 never charges money, only records what *would* be owed.

## 6. Booking detail and history

```
GET /api/v1/bookings/{id}
GET /api/v1/customers/me/rides      (paginated, docs/api/customer-profile.md section 6)
```

Detail response:

```json
{
  "id": "...", "status": "SEARCHING_DRIVER",
  "pickupAddress": "...", "destinationAddress": "...",
  "vehicleType": "CAB", "paymentMethod": "CASH",
  "estimatedFare": 18500, "finalFare": null, "currency": "INR",
  "ridePin": "4821",
  "driver": null,
  "createdAt": "...", "scheduledAt": null
}
```

`driver` is `null` until `status` reaches `DRIVER_ASSIGNED` or later, then populated from `Driver` + `User` + `Vehicle` (name, photo key, rating average, vehicle make/model/colour/registration) — per `docs/product/user-flows.md` §2.2 step 6. `ridePin` per §3. History list items are the same shape minus `ridePin` and `driver` detail.

Ownership check on `GET /bookings/{id}`: `404`, not `403`, for a booking that exists but belongs to a different customer.

## 7. Eligibility (D5) — placeholder only, with a startup guard

D5 itself is **still undecided** — not invented here. The roadmap's default ("women riders and women drivers; accompanied minors allowed") is not implemented as an enforced rule, because enforcing it would need data the schema doesn't have (no gender field on `users`/`customers`) and a verification process nobody has designed.

```java
public interface EligibilityPolicy {
    EligibilityDecision checkCustomerEligibility(Customer customer);
    EligibilityDecision checkCompanions(BookingRequest request);
}

record EligibilityDecision(boolean eligible, String reasonIfIneligible) {}
```

`DefaultEligibilityPolicy` — `@Profile({"dev", "test"})`, same restriction pattern as `MockSmsService` — returns `eligible = true` unconditionally from both methods, commented plainly as a placeholder. It's wired into booking creation (§2, right after loading the customer, before the state machine starts).

**New**: `EligibilityPolicyStartupGuard`, mirroring `SmsStartupGuard` exactly — if `staging`/`prod` is active and no real (non-default) `EligibilityPolicy` bean is configured, startup fails fast with a clear message, the same way staging/prod already refuse to start with the mock SMS provider. This means staging/prod **cannot start at all** once this ships, until D5 is actually decided and a real policy is built — intentional: it keeps "we haven't decided this yet" from silently becoming "we shipped with no eligibility check in a real environment."

## 8. New error codes

- `ErrorCode.OUT_OF_SERVICE_AREA` (`422`, *"Lady Go is not available in this area yet."*)
- `ErrorCode.FARE_CHANGED` (`422`, *"The fare has changed since your estimate."* — response body carries the fresh estimate, handled by a controller-local exception handler rather than the generic one, since this is the one error that must return data alongside the failure)
- `ErrorCode.MAPS_UNAVAILABLE` (`503`, *"Maps service is temporarily unavailable."*)
- `ErrorCode.LIMIT_EXCEEDED` (`422`, shared with `docs/api/customer-profile.md` §5)

## 9. Decisions (2026-10-04)

| # | Decision |
|---|----------|
| B1 | Fare estimate response returns the breakdown *and* the total. |
| B2 | Booking creation always recomputes the fare server-side; the client's `expectedFare` is a staleness check only, rejected with `FARE_CHANGED` + fresh quote beyond a configurable tolerance (`booking.fare.change.tolerance.pct`, default 5%). |
| B3 | `V13` adds `bookings.idempotency_key` + `UNIQUE (customer_id, idempotency_key)`, with constraint tests. |
| B4 | Ride PIN is derived via `HMAC(LADYGO_RIDE_PIN_SECRET, bookingId)`, never stored; verification is rate-limited and lockable per ride via Phase 3's `RateLimiter`. |

Implementation follows; see `docs/development/roadmap.md` Phase 4 for exit criteria.
