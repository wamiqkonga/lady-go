# Lady Go — Fare Estimate, Booking, Booking State Machine (Phase 4)

Status: Draft v0.1 · for approval before implementation.

Scope: `bookings` and `pricing` modules. Builds on tables already migrated in Phase 2 (`bookings`, `booking_events`, `ride_offers`, `pricing_rules` — `V5__pricing.sql`, `V6__bookings_events_offers.sql`) and the entities/repositories already written there, including `BookingRepository.findByCustomerIdOrderByCreatedAtDesc` (ride history, already built) and `PricingRuleRepository.findActive` (already built, exactly the lookup fare calculation needs). No schema changes except the one new `configuration` seed row noted in §5.

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

1. Resolve the serving city from the **pickup point**, not the customer's `default_city_id` — a customer travelling outside their home city should still get a real estimate for where they actually are. `SELECT id FROM cities WHERE active AND ST_Contains(service_area, :pickupPoint)`. If no city's `service_area` contains the point: `422` `OUT_OF_SERVICE_AREA` (new error code, §5) — not a generic `VALIDATION_FAILED`, so clients can show a specific "we don't operate here yet" message instead of a form-validation-style error.
2. `MapsService.calculateRoute(pickup, destination)` for real distance/duration (not straight-line — see `docs/api/maps-service.md` §4, this is exactly the "fare estimate" call ADR-011 earmarks for the Routes API).
3. `ride.max.distance.m` (already seeded, Phase 2) caps trip distance — over the limit is `422` `VALIDATION_FAILED` with a clear message, not silently truncated.
4. `PricingRuleRepository.findActive(cityId, vehicleType, now)` — `404` `RESOURCE_NOT_FOUND` if no rule is configured for that city+vehicle type yet (an operational gap, not a customer error, but still needs a clean response).
5. `FareCalculator.estimate(rule, distanceM, durationS)`:

```
distanceFare = rule.perKm * (distanceM / 1000.0)
timeFare     = rule.perMinute * (durationS / 60.0)
subtotal     = rule.baseFare + distanceFare + timeFare
serviceFee   = subtotal * (rule.serviceFeePct / 100) + rule.serviceFeeFlat
total        = max(rule.minimumFare, subtotal) + serviceFee
```

All money in minor units (ADR-007), rounded to the nearest minor unit at each step the way `docs/database/database-design.md` conventions imply (no floating-point fare ever reaches a response body).

Response:

```json
{
  "cityId": "...", "vehicleType": "CAB", "currency": "INR",
  "distanceMeters": 7400, "durationSeconds": 1140,
  "estimatedFare": 18500,
  "breakdown": { "baseFare": 5000, "distanceFare": 8880, "timeFare": 3800, "serviceFee": 820 },
  "pricingRuleId": "..."
}
```

The breakdown is returned for transparency (`docs/product/requirements.md` FR-BKG-1 just says "fare estimate," the breakdown is a judgment call, OQ-B1) but **nothing from this response is trusted back from the client** — see §2.

## 2. Create a booking

```
POST /api/v1/bookings
Idempotency-Key: <client-generated UUID>
```

`ROLE_CUSTOMER`. Request carries only what the customer chose, never any number the server already computed:

```json
{
  "pickupLatitude": 34.08, "pickupLongitude": 74.80, "pickupAddress": "...",
  "destinationLatitude": 34.13, "destinationLongitude": 74.87, "destinationAddress": "...",
  "vehicleType": "CAB", "paymentMethod": "CASH", "scheduledAt": null
}
```

No `estimatedFare`, `distanceMeters`, `pricingRuleId`, or anything else from the `/estimate` response is accepted here — the server repeats exactly the §1 computation (city resolution, route, active pricing rule, fare) itself and persists *that* result. This closes the entire "client sends a stale or edited fare" problem by construction, at the cost of the fare shown at booking time possibly differing very slightly from the estimate shown a few seconds earlier (traffic conditions moved) — the same tradeoff every real ride-hailing app makes, and simpler than reconciling a client-supplied estimate against a tolerance (OQ-B2 if you'd rather accept the client's `pricingRuleId` as a hint and only reject if it's no longer active, saving a few API calls).

`scheduledAt`: accepted and persisted (the column exists), but Phase 4 does not act on it differently from an immediate booking — scheduled-ride handling (don't search for a driver until close to the scheduled time) is explicitly post-MVP (`docs/product/requirements.md` §4.2). A non-null value just gets stored for whenever that ships.

`Idempotency-Key`: required (per `docs/architecture/backend-architecture.md` §2, booking creation is one of the two endpoints that must have it). First request with a given key creates the booking; a repeat with the same key and the same customer returns the original booking (`200`, not `201`) rather than creating a duplicate — needs a small lookup table or a unique index on `(customer_id, idempotency_key)`; **this is a schema addition** (no such column exists on `bookings` today) and is flagged as OQ-B3 rather than silently added, since schema changes are the one thing this document was told not to make unilaterally.

On success: insert `bookings` row at `REQUESTED`, write the first `booking_events` row (`from_status = NULL`, `to_status = 'REQUESTED'`, `actor_type = 'CUSTOMER'`), then immediately run the state machine's `REQUESTED → SEARCHING_DRIVER` transition (§4) in the same transaction and write the second `booking_events` row (`actor_type = 'SYSTEM'`, `reason = 'awaiting_matching'`). Response is the booking detail shape (§6) at `SEARCHING_DRIVER`, plus the ride PIN (§3).

Eligibility (D5) is checked here, through the placeholder interface — §7.

## 3. The ride PIN problem

`bookings.ride_pin_hash` is one-way (Phase 2's naming and the `docs/database/database-design.md` convention of hashing exactly the way OTP codes are hashed). But the customer needs to **see** the plaintext PIN (to read it out to the driver at pickup), potentially more than once — if they background the app and come back, the PIN must still be visible until the ride actually starts. A one-way hash can't be un-hashed to show them again.

Proposed mechanism: a short-lived, in-process cache (`RidePinCache`, same Caffeine mechanism as `docs/api/maps-service.md` §4's geocode cache) holding the **plaintext** PIN keyed by `bookingId`, generated at booking creation, with a TTL generous enough to cover a realistic search-to-pickup window (proposed 60 minutes, OQ-B4). `GET /bookings/{id}` includes a `ridePin` field, populated from this cache, only when: the caller is the booking's own customer, the booking's status is before `RIDE_STARTED`, and the cache still holds it (cache miss after TTL or instance restart simply means the field is omitted — acceptable since a PIN that's been sitting unused for an hour on a still-searching booking is an edge case, not the common path). The driver-side PIN check (Phase 5) only ever compares against `ride_pin_hash`; this cache is purely a customer-display convenience and never consulted for verification.

This is a real design decision, not a trivial detail — flagged explicitly (OQ-B4) because the alternative (store it encrypted-and-reversible instead of hashed, like Phase 3's `MfaSecretCipher` pattern) would mean revisiting the Phase 2 column's one-way semantics, which is out of this document's mandate to change unilaterally.

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
2. `to` must be in `ALLOWED.get(booking.status)`, else `BookingStateException` (`409`, `BOOKING_INVALID_STATE` — error code already exists from Phase 1/2, nothing new needed).
3. Authorise the actor against the transition: a `CUSTOMER` may only ever *initiate* a transition to `CANCELLED` (from an allowed pre-`RIDE_STARTED` state) — every other transition is driven by `SYSTEM` (matching, Phase 6) or `DRIVER`/`ADMIN` in later phases. Phase 4 only ever exercises `REQUESTED→SEARCHING_DRIVER` (system) and `*→CANCELLED` (customer); the rest of the table exists now so Phase 5/6/8 don't need to touch this service, matching the architecture doc's intent that the whole graph lives in one place from the start.
4. Persist the new status, write one `booking_events` row (append-only, already enforced by a DB trigger — `prevent_update` — so there's no way to silently rewrite history even from a bug), publish a `BookingStatusChangedEvent` (Spring `ApplicationEvent`, in-process; nothing subscribes to it yet in Phase 4 except whatever minimal logging/audit hook is useful, and `MatchingService` in Phase 6 is the first real subscriber).

**Why `RIDE_STARTED` has no `CANCELLED` edge**: `docs/product/user-flows.md` §1 is explicit — "After `RIDE_STARTED` a customer cannot cancel; they can only end the ride via the support/safety path." Phase 4 enforces this by simply not having the edge in the table; the support/safety path (Phase 10) will use a different, admin/support-authorised transition, not the customer cancellation endpoint.

## 5. Cancellation

```
POST /api/v1/bookings/{id}/cancel
```

`ROLE_CUSTOMER`, body `{ "reason": "..." }` (free text, required). Calls `BookingStateMachine.transition(id, CANCELLED, CUSTOMER, reason)`. Fee logic (D7 default: "free grace window, then flat fee"):

- `REQUESTED` / `SEARCHING_DRIVER`: always free (no driver committed yet).
- `DRIVER_ASSIGNED` through `DRIVER_ARRIVED`: free if cancelled within `cancellation.grace.seconds` (already seeded, Phase 2) of the `DRIVER_ACCEPTED` transition timestamp (read from that `booking_events` row — the append-only log is the source of truth for "when did the driver accept," not a new column); otherwise the active `pricing_rules.cancellation_fee` for the booking's city+vehicle type applies.

There is no column on `bookings` to hold an assessed-but-not-yet-charged cancellation fee (Phase 2 didn't add one, and this document doesn't add schema). The assessed fee is recorded in the `CANCELLED` `booking_events` row's `metadata` jsonb (`{"cancellationFee": 5000, "currency": "INR"}`) — that table exists exactly for this kind of transition-specific detail, and Phase 8 (Payments) reads it from there when it actually charges anything; Phase 4 never charges money, only records what *would* be owed.

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

`driver` is `null` until `status` reaches `DRIVER_ASSIGNED` or later, then populated from `Driver` + `User` + `Vehicle` (name, photo key, rating average, vehicle make/model/colour/registration) — per `docs/product/user-flows.md` §2.2 step 6. `ridePin` per §3. History list items are the same shape minus `ridePin` and `driver` detail (a summary list doesn't need either) — exact trimmed fields are an implementation detail, not a design question.

Ownership check on `GET /bookings/{id}`: `404`, not `403`, for a booking that exists but belongs to a different customer (same reasoning as saved locations in `docs/api/customer-profile.md` §3 — don't confirm existence to someone who shouldn't see it).

## 7. Eligibility (D5) — placeholder only, per instruction

D5's roadmap default — "women riders and women drivers; accompanied minors allowed" — is **not** implemented as an enforced rule in Phase 4, because enforcing it would need data Phase 2's schema doesn't have (no gender field anywhere on `users` or `customers`) and a verification process nobody has designed. Building real enforcement now would mean inventing both the data model and the verification UX unilaterally, which is exactly what was ruled out.

Instead, a clearly marked seam:

```java
public interface EligibilityPolicy {
    EligibilityDecision checkCustomerEligibility(Customer customer);
    EligibilityDecision checkCompanions(BookingRequest request);   // minors/companions riding along
}

record EligibilityDecision(boolean eligible, String reasonIfIneligible) {}
```

`DefaultEligibilityPolicy` — the only implementation Phase 4 ships — returns `eligible = true` unconditionally from both methods, with a comment stating plainly that this is a placeholder and real gender/age verification is not implemented. It's wired into booking creation (§2, called right after loading the customer, before the state machine starts) so the call site exists and later phases implement real policy behind the same interface without touching booking logic again. `BookingRequest` carries a `companionCount`/`travelingWithMinor` field already, so the interface has something to check once a real policy exists, even though `DefaultEligibilityPolicy` ignores it today.

## 8. New error code

`ErrorCode.OUT_OF_SERVICE_AREA` (`422 UNPROCESSABLE_ENTITY`, default message *"Lady Go is not available in this area yet."*) — distinct from `VALIDATION_FAILED` so clients can show a specific message rather than a generic form error.

## 9. Open questions

| # | Question | Default if unanswered |
|---|----------|------------------------|
| OQ-B1 | Return a fare breakdown (base/distance/time/service fee) or just the total? | Return the breakdown |
| OQ-B2 | Booking creation: always recompute fare server-side from scratch (simpler, zero tamper surface), or accept the `/estimate` response's `pricingRuleId` as a hint to skip one lookup, still re-validating it's still active? | Always recompute from scratch |
| OQ-B3 | Idempotent booking creation needs a schema addition (`(customer_id, idempotency_key)` uniqueness) — approve that one addition, or handle idempotency a different way (e.g. a short-lived cache of recent booking-creation requests, no schema change, but weaker guarantees across restarts)? | Schema addition (durable, correct across restarts) — but not added without your sign-off since it wasn't pre-approved |
| OQ-B4 | Ride PIN customer-visibility cache: in-process Caffeine with a 60-minute TTL, as described in §3, or a different mechanism/duration? | As described, 60 minutes |

No code has been written for this phase yet.
