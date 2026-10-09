# Ride lifecycle — design (Phase 6.5 / pre-Payments)

Status: design draft, approved decisions baked in below, open questions at the end. No code yet.

## 0. Why this phase, and why before Payments

`BookingStatus` already defines `DRIVER_ARRIVING → DRIVER_ARRIVED → RIDE_STARTED → RIDE_COMPLETED →
PAYMENT_PENDING → COMPLETED`, and `Booking.finalFare`, `Ride.startedAt/completedAt/startLocation/
endLocation/actualDistanceM/actualDurationS` all exist as columns. Nothing drives any of it today —
the only writer was `RideBootstrapListener` (since renamed `RideStatusSyncListener` — see the root-cause
fix below), which created a bare `ASSIGNED` row on `DRIVER_ACCEPTED` purely as an FK target for
`location_history`, and left every other transition untouched. Payments (Phase 8) needs a completed ride with a real
`finalFare` to charge against; building it now would mean building it against `null`. This phase closes
that gap and is the direct prerequisite for Payments.

## 1. State machine

**Booking status** (existing enum, unchanged):
```
DRIVER_ACCEPTED → DRIVER_ARRIVING → DRIVER_ARRIVED → RIDE_STARTED → RIDE_COMPLETED → PAYMENT_PENDING → COMPLETED
```
Driver-initiated at every step except the two fare/payment transitions, which are system-initiated
(fare computed at `RIDE_COMPLETED`; `PAYMENT_PENDING → COMPLETED` is either an auto-sweep for cash,
or Phase 8's job for online).

**Ride status** (existing enum, unchanged — `ASSIGNED, STARTED, COMPLETED, CANCELLED`): tracks only the
in-vehicle boundary. `ASSIGNED` (set at `DRIVER_ACCEPTED`) → `STARTED` (at `RIDE_STARTED`) →
`COMPLETED` (at `RIDE_COMPLETED`, including an early end — see §4). `CANCELLED` is set when the
booking is abandoned post-acceptance — see "Root-cause fix" below; this was a gap in the original
version of this phase, not a deliberate design choice.

**No plain mid-ride cancel.** Once `RIDE_STARTED`, cancellation is replaced entirely by the early-end
path in §4 — there is no "cancel and walk away" after the meter's running, per your decision.

**Root-cause fix — `Ride.status` / driver-availability drift (post-pilot-plan security audit)**:
the original `RideBootstrapListener` only ever wrote `Ride.status = ASSIGNED` on `DRIVER_ACCEPTED`
and never touched it again — a booking cancelled after acceptance left its `Ride` row sitting at
`ASSIGNED` forever, which is what the trip-share leak (docs/api/safety.md §4) actually exploited.
Replaced by `RideStatusSyncListener`, which reacts to every `BookingStatusChangedEvent` that can
affect a ride: `DRIVER_ACCEPTED` creates or revives the row (handling reacceptance after a bounce-back
to `SEARCHING_DRIVER`, since `rides.booking_id` is unique — the existing row is updated, never
duplicated); `CANCELLED`/`SEARCHING_DRIVER` mark the ride `CANCELLED` (unless already `COMPLETED`);
`RIDE_COMPLETED` is unchanged for `Ride.status` (still written by `completeRideInternal`) but now
also releases the driver. That last point was a second, independently-significant finding from the
same audit: `DriverRepository.releaseFromOffer` (`BUSY → ONLINE`) was only ever called from
offer-reject/expire, never from ride completion or post-acceptance cancellation, so every driver got
stuck `BUSY` forever after their first ride, with no self-service recovery
(`DriverAvailabilityService.setAvailability` refuses to change availability away from `BUSY`
directly). Fixed using the existing `releaseFromOffer` primitive rather than introducing the unused
`ON_RIDE` enum value, which is a separate, larger product question (out of scope here).
`location_history` sampling and `BookingVisibilityPolicy` were both audited for the same drift and
found clean — both key on `Booking.status`, never `Ride.status`. One state test per transition in
`RideStatusSyncListenerTest`.

## 2. Endpoints

All driver-side actions take `bookingId`, resolve the driver via `drivers.findByUserId`, and reuse
`BookingStateMachine.transition` for the status write (same audit-event path every other transition
already goes through).

1. `POST /api/v1/drivers/me/rides/{bookingId}/arriving` — `DRIVER_ACCEPTED → DRIVER_ARRIVING`.
   Idempotent re-call while already `DRIVER_ARRIVING` is a no-op, not an error (driver app may retry).
2. `POST /api/v1/drivers/me/rides/{bookingId}/arrived` — `DRIVER_ARRIVING → DRIVER_ARRIVED`. Gated by
   the proximity+freshness check in §3 against `booking.pickupLocation`. Hard reject outside it.
3. `POST /api/v1/drivers/me/rides/{bookingId}/start` body `{ pin }` — `DRIVER_ARRIVED → RIDE_STARTED`.
   Requires `RidePinService.verify(bookingId, pin)` (see §5) **and** the same proximity+freshness gate
   against `pickupLocation` (driver may have moved between arriving and PIN entry). Sets
   `ride.startedAt`, `ride.startLocation` = driver's current location at that moment, `ride.status =
   STARTED`.
4. `POST /api/v1/drivers/me/rides/{bookingId}/complete` — `RIDE_STARTED → RIDE_COMPLETED`. Normal,
   expected end of trip. Computes fare (§6), sets `ride.completedAt`, `endLocation`,
   `actualDistanceM`/`actualDurationS` (§7), `ride.status = COMPLETED`. Proximity check against
   `destinationLocation` is **soft** here — far-from-destination is flagged (§4), never blocked, since
   genuine destination changes mid-ride are expected and this endpoint is the normal path for them.
5. `POST /api/v1/drivers/me/rides/{bookingId}/end-early` and the rider-side
   `POST /api/v1/customers/me/bookings/{bookingId}/end-early` — either party, only while `RIDE_STARTED`.
   Always flagged (§4), fare recomputed from actual distance with the minimum-fare floor (§6).
6. `POST /api/v1/drivers/me/rides/{bookingId}/confirm-cash-collected` — only once `PAYMENT_PENDING` and
   `paymentMethod == CASH`. Starts the dispute window (§8).
7. `POST /api/v1/customers/me/bookings/{bookingId}/dispute-cash` — rider-side, only within the dispute
   window. Creates a `RideFlag(CASH_DISPUTE)` (§4), keeps the booking at `PAYMENT_PENDING` for admin
   review (admin resolution UI is Phase 9's job; this phase only needs the durable flag record).
8. `POST /api/v1/customers/me/bookings/{bookingId}/ride-flag/respond` body `{ action: ACKNOWLEDGE |
   DISPUTE }` — the rider's response to a flag that prompts them (the "driver ended far from
   destination" case in §4 reuses this same endpoint rather than inventing a second response mechanic).

## 3. Proximity + freshness gate (arrived, start)

Reuses the driver's `currentLocation`/`currentLocationAt` already written by `DriverLocationService`
(docs/api/matching.md §3) — no new location write path.

- **Freshness**: `currentLocationAt` (server receive time) must be within `ride.gate.max_location_age.
  seconds` (default 30s — tighter than the general `driver.location.max_age.seconds` = 60s, because a
  gate decision is a point-in-time safety check, not a speed-check baseline) of the gate check itself.
  Stale: reject, ask the driver to send a fresh location update and retry — never fall back to a stale
  point for a safety gate.
- **Proximity**: `haversineMeters(currentLocation, pickupLocation) <= ride.gate.pickup_radius.m +
  min(currentLocationAccuracyM, ride.gate.max_accuracy_credit.m)`. Default `pickup_radius.m` = 200 (your
  number), `max_accuracy_credit.m` = 100 — accuracy widens the effective radius (GPS error means the
  true position could be anywhere within its accuracy circle) but is capped so a driver can't report an
  inflated accuracy value to spoof "near enough" from anywhere.
- **Requires a new field**: `DriverLocationRequest` gets `accuracyMeters` (nullable `Double`, from the
  device's own GPS accuracy reading) alongside `latitude/longitude/recordedAt`; `Driver` gets a parallel
  `currentLocationAccuracyM` column, written by `DriverLocationService.updateLocation` the same call as
  `currentLocation`/`currentLocationAt`. A missing/null accuracy is treated as 0 credit (strictest case,
  not "trust it unconditionally"). **This is a mobile API contract change — flagging, not deciding; the
  driver apps need to start sending it.**

## 4. `RideFlag` — the one durable record behind every "admin should look at this" case

A new, minimal entity — deliberately not reusing `EligibilityReport` (verification module, scoped to
person-to-person eligibility concerns — gender mismatch, misconduct) since these are operational/billing
signals, a different concern that would otherwise pollute that queue's semantics. Phase 10 (Safety)
builds the admin console on top of this; this phase only needs the record to exist.

```
ride_flags
  id, ride_id, booking_id, type, raised_by (DRIVER|RIDER|SYSTEM),
  distance_from_expected_m (nullable), rider_response (nullable: ACKNOWLEDGED|DISPUTED),
  rider_responded_at (nullable), created_at
```

`type` ∈ `EARLY_END`, `COMPLETION_FAR_FROM_DESTINATION`, `PIN_LOCKOUT`, `CASH_DISPUTE`.

- **`EARLY_END`**: always raised on §2.5. If driver-initiated **and** far from `destinationLocation`
  (same radius config as the completion check), also pushes the rider a prompt via
  `RealtimePushService.pushToUser` (IN_APP) to acknowledge or dispute via §2.8 — this is the "alert
  admins and prompt the rider" case. Rider-initiated early end never prompts the rider (they're the
  one who did it) but still raises the flag and still alerts admins.
- **`COMPLETION_FAR_FROM_DESTINATION`**: raised on a normal §2.4 complete whose `endLocation` is outside
  the same radius from `destinationLocation`. Never blocks the completion.
- **`PIN_LOCKOUT`**: raised when §5's attempt cap trips. Always notifies the rider and frees their
  cancel (§5).
- **`CASH_DISPUTE`**: raised by §2.7.

## 5. Ride PIN — reusing `RidePinService` as-is, no stored hash

`RidePinService.derive`/`verify` (HMAC-SHA256 over `bookingId`, nothing persisted,
`bookings.ride_pin_hash` deliberately unused) is already exactly what you specified — no change to the
derivation. What's missing, to add:

- **Per-ride lockout beyond the existing rolling-window rate limit**: today, `verify` throws
  `RateLimitExceededException` once `ride.pin.max.attempts` (already 5, already your number) is
  exceeded inside the 15-minute window, but that's a *cooldown*, not a lock — after 15 minutes the
  driver can try again. Add a durable `ride_pin_lockouts` row (or a `Booking.ridePinLockedAt` column —
  leaning toward the column, it's a 1:1 fact about the booking, not worth a new table) set the moment
  the 5th wrong attempt lands, independent of the rolling window. Once locked: `start` is rejected
  outright (not just rate-limited) until admin intervention, `RideFlag(PIN_LOCKOUT)` is raised, the
  rider is notified (`RealtimePushService.pushToUser`), and the rider gets a **fee-free** cancel path
  (reuse the existing `cancelForEligibilityConcern`-style zero-fee cancel, but this isn't an eligibility
  concern — needs its own `cancelForPinLockout`-shaped method or a generalized "fee waiver reason" on
  the existing cancel, to avoid a third near-duplicate cancel method; leaning toward generalizing).
- **Per-driver rate limit across rides**: a new `RateLimiter` bucket keyed by `driverId` alone (not
  `bookingId`), e.g. `ride_pin_driver:{driverId}`, config `ride.pin.driver.max.attempts` /
  `ride.pin.driver.window.minutes` — catches a driver burning through many different riders' PINs
  rather than brute-forcing one. Checked in addition to, not instead of, the per-ride bucket.

## 6. Fare — the estimate is the final fare by default

Per your decision: `booking.finalFare = booking.estimatedFare` by default at `RIDE_COMPLETED` — no
recalculation on a normal trip, regardless of how actual distance/duration compared to the estimate.

**Correction to an earlier claim in this doc**: a previous revision said early-end's recompute uses
"no client-supplied value" for pricing. That's not quite right, and the imprecision mattered:
`actualDistanceM`/`actualDurationS` are computed server-side, but FROM `location_history`, which is
populated from the **driver's own device's** periodic location reports (docs/api/matching.md §3). The
early-end HTTP request itself carries no distance/duration/location field - but the device-reported
GPS trail those numbers are derived from is not independently-verified ground truth either; a
compromised or colluding driver app is the same already-accepted residual risk documented in
docs/api/matching.md §3's anti-spoofing section. The policy below is designed so that risk, even at
its worst, bounds how much it can cost the rider - see the cap in the "genuine progress" row.

### Early-end fare policy, by initiator × progress

| Initiator | Progress | Fare | Notes |
|---|---|---|---|
| Rider | None (fails `isGenuinelyEarly`) | Normal cancellation fee (`rule.getCancellationFee()`) | **Not** waived by default - this is the ordinary post-acceptance cancellation cost, now reached via a different action. Flagged (`EARLY_END`) for admin review. |
| Rider | None, **safety-tagged** | Waived (0), unless the shared safety-cancel cap (weekly/pair, same budget as the pre-start `cancelForSafetyConcern`) is exceeded - then the normal fee applies anyway | Raises `RIDER_SAFETY_CANCEL` (shared cap counter) **and** `EARLY_END`. Beyond the cap, still flagged `capExceeded` for priority review. |
| Driver | None | **Always** waived (0) - unconditional, no cap | The driver forgoes their own payout here; no abuse incentive the way a free rider exit would have. Flagged (`EARLY_END`) for admin review regardless. |
| Rider or Driver | Genuine progress | `min(FareCalculator.calculate(rule, actualDistanceM, actualDurationS).totalFare(), booking.estimatedFare)` | Never exceeds the estimate - the cap mentioned above. Can land below it (a real short trip) or hit the cap exactly (a trip that ran long). |
| Rider or Driver | Reached the actual destination (just ended via this action instead of `complete`) | Same formula as "genuine progress" - no separate case needed, since `actualDistanceM`/`actualDurationS` will be close to the full trip and the `min()` cap naturally resolves to (at most) the estimate | Not a distinct code path - this row exists to make clear the formula already covers it correctly. |

"Genuinely early" (the no-progress row) is still gated by `ride.early_end.min_elapsed.seconds`
(120) **AND** `ride.early_end.min_distance.m` (500) both being met - either alone is insufficient (see
the follow-up finding below).

**Security review findings on the original version of this table**:
- The first fix charged the **full estimate** (not a cancellation fee) below the threshold, for
  every actor uniformly - no distinction by initiator, no cancellation-fee framing, no waiver logic.
  Replaced with the table above per your explicit correction.
- That first fix's threshold used OR, not AND, between the elapsed-time and distance conditions -
  elapsed time alone (parked, doing nothing, zero actual distance) satisfied it, so waiting ~120s and
  then ending early still got the lower recompute with no real travel. Fixed to AND.
- The recompute itself was uncapped - a real or spoofed-inflated actual distance/duration could in
  principle push the recomputed fare ABOVE the original estimate. Fixed with the `min(computed,
  estimate)` cap in the table above, which is also what bounds the device-location-trust residual
  risk's financial impact for this specific path.

- **Rider-changed destination mid-ride**: out of this phase's explicit scope (no destination-change
  endpoint exists yet) — noted here only because it's the other case you named for recalculation; if you
  want it in this phase rather than deferred, flag it and I'll design the endpoint.

## 7. Distance/duration for the record — unchanged source, new consumer

`location_history` is already sampled during `RIDE_STARTED`/other active statuses by
`DriverLocationService.sampleLocationHistoryIfOnActiveRide` (docs/api/matching.md §3). At `complete`/
`end-early`, sum consecutive-point haversine distances for that `ride_id` (ordered by `recorded_at`) into
`actualDistanceM`, and `completedAt - startedAt` into `actualDurationS`. This is a read, not a new write
path — no change to the sampling logic itself.

## 8. Cash confirmation and the dispute window

New `Booking.cashConfirmedAt` and `Booking.cashDisputedAt` (nullable timestamps on the booking itself,
not a separate table). Flow, `paymentMethod == CASH` only:

1. `RIDE_COMPLETED → PAYMENT_PENDING` happens automatically at §2.4/§2.5 completion, same as today's
   enum ordering implies.
2. Driver calls §2.6, setting `cashConfirmedAt = now()`.
3. Rider has `ride.cash.dispute.window.minutes` (default 60, configurable) from `cashConfirmedAt` to call
   §2.7, which sets `cashDisputedAt = now()` **on the booking itself** and raises `RideFlag(CASH_DISPUTE)`
   for the admin queue (Phase 9). **Security review finding, HIGH**: the first implementation only
   raised the flag, never writing to the `bookings` row - with nothing to bump that row's `@Version`,
   a dispute landing in the same window as a sweep tick (§8.4) could lose the race silently (the sweep
   reads "no dispute" and completes the booking anyway, the dispute flag existing alongside a
   now-already-COMPLETED booking). `cashDisputedAt` gives the dispute an actual write to the same row
   the sweep writes to, so a race now fails the sweep's attempt with the ordinary
   `OptimisticLockingFailureException` every other concurrent booking transition already relies on,
   rather than two operations silently succeeding against each other's assumptions.
4. A `@Scheduled` sweep job (same shape as `OfferSweepJob`) finds `PAYMENT_PENDING`/`CASH` candidates,
   then re-reads and completes each one in its **own** transaction (via a genuinely separate bean call,
   not same-bean self-invocation, which Spring's proxy-based `@Transactional` would silently ignore),
   gated on `cashConfirmedAt IS NOT NULL AND cashDisputedAt IS NULL AND now() - cashConfirmedAt >
   dispute.window`. A per-booking optimistic-lock conflict is caught and skipped (same resilience shape
   as `OfferSweepJob`'s own concurrent-accept handling), not allowed to abort the rest of the batch.
   `ONLINE` bookings are left at `PAYMENT_PENDING` for Phase 8 to pick up - no auto-complete for those,
   since there's no cash-collection fact to confirm against.

**Two follow-up findings** on top of the HIGH fix above:
- `disputeCash` was a plain read-then-write with no guard against a repeat call - a flaky client retry
  (or two overlapping requests) could each pass "not yet disputed" and both insert their own
  `RideFlag`, duplicating the admin queue entry. Fixed with an atomic conditional claim
  (`BookingRepository.markCashDisputedIfNotAlready`, same shape as `RideOfferRepository.
  expireIfStillOffered`) - 0 rows affected means already disputed, treated as an idempotent success
  rather than a second flag.
- The sweep's per-booking catch only caught `OptimisticLockingFailureException` - any OTHER
  unexpected failure on one booking (e.g. a genuinely missing row) propagated out of the loop and
  aborted that whole tick, silently skipping every other eligible booking in the same batch too, not
  because anything was wrong with them. Widened to catch any `RuntimeException` per candidate so one
  booking's problem never blocks another's; the next tick still retries the failed one.

## 9. Anti-spoofing: how the known residual risk interacts with this phase

docs/api/matching.md §3 already documents the residual risk in the location-staleness design: an
attacker can claim an inflated device-time gap, capped at `max_age.seconds` (60s) per call but not fully
closed against chaining across many calls. This phase's gates (§3) don't inherit that risk the same
way, because they don't use the device-delta speed calculation at all — they check the driver's
*currently stored* `currentLocation`/`currentLocationAt`/`currentLocationAccuracyM` against a fixed
radius at the moment of the gate check, with their own independent freshness bound
(`ride.gate.max_location_age.seconds` = 30s). The teleport-speed check and the arrival/start gate are
two separate defenses: a driver who successfully spoofs their way past the speed check (within its
documented residual-risk bound) still has to have a `currentLocation` within 300m of the real pickup
*at the moment they tap "arrived"/"start"* to pass this phase's gate — spoofing a single stationary point
convincingly is a materially different (and higher-effort, GPS-mock-app-level) attack than gaming a
delta calculation, and is unchanged by this phase either way. Not claiming this closes the residual risk
end-to-end; noting it doesn't compound with it either.

## 10. New migration (V20, draft)

```sql
ALTER TABLE drivers ADD COLUMN current_location_accuracy_m real;
ALTER TABLE bookings ADD COLUMN ride_pin_locked_at timestamptz;
ALTER TABLE bookings ADD COLUMN cash_confirmed_at timestamptz;

CREATE TABLE ride_flags (
    id                          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    ride_id                     uuid NOT NULL REFERENCES rides (id),
    booking_id                  uuid NOT NULL REFERENCES bookings (id),
    type                        varchar(40) NOT NULL,
    raised_by                   varchar(10) NOT NULL,
    distance_from_expected_m    integer,
    rider_response              varchar(15),
    rider_responded_at          timestamptz,
    created_at                  timestamptz NOT NULL DEFAULT now()
);

INSERT INTO configuration (key, value, type, scope, description) VALUES
    ('ride.gate.pickup_radius.m',            '200', 'INTEGER', 'GLOBAL', 'Max distance from pickup to allow arrived/start'),
    ('ride.gate.max_accuracy_credit.m',      '100', 'INTEGER', 'GLOBAL', 'Cap on how much reported GPS accuracy widens the gate radius'),
    ('ride.gate.max_location_age.seconds',   '30',  'INTEGER', 'GLOBAL', 'Freshness bound for the arrived/start/completion-check location'),
    ('ride.pin.driver.max.attempts',         '15',  'INTEGER', 'GLOBAL', 'Max PIN attempts per driver across all rides, in the window below'),
    ('ride.pin.driver.window.minutes',       '60',  'INTEGER', 'GLOBAL', 'Window for the per-driver PIN attempt limit'),
    ('ride.cash.dispute.window.minutes',     '60',  'INTEGER', 'GLOBAL', 'Rider window to dispute a cash-collected confirmation before auto-complete');
```

(Draft — exact column types/names to firm up at implementation time; shown here to make the scope of
schema change visible for approval, not as final DDL.)

## 11. Roadmap status (actual vs. documented)

`docs/development/roadmap.md` currently says "Phases 0–3 closed; Phase 4 (Customer) design in progress"
— stale. Actual state, from commits and CI: Phase 4 (Customer/booking), Phase 5 (Driver onboarding),
Phase 6 (Matching/offers), and a meaningful chunk of Phase 7 (WebSocket/Redis infra, not yet admin live
map or customer-facing live tracking UI) are built and green. This design proposes **inserting this ride-
lifecycle work ahead of Phase 8 Payments** (it's Phase 5/6's own stated outcome — "ride lifecycle" is
literally named in Phase 5's row — just not yet built), and planning **SOS + trip sharing (the ride-PIN-
adjacent half of Phase 10 Safety) as the phase immediately after this one, still ahead of Payments** —
your instruction. I'll update the roadmap doc itself (phase closures, the reordering note) once this
design is approved and implemented, rather than rewriting it now against work that doesn't exist yet.

## 12. Next phase after this one: SOS + trip sharing (planned, not designed yet)

Per your instruction, planning this as the immediately-following phase, before Payments:

- SOS: an in-ride panic action, admin queue to triage (ties into the same `RideFlag`-adjacent pattern,
  or its own table — to be decided in that phase's design), emergency-contact notification.
- Trip sharing: `TripShare` domain already exists (empty — `backend/src/main/java/com/ladygo/rides/
  domain/TripShare.java`, unused so far); a share-link endpoint exposing a read-only live-location view
  to a non-user recipient, bounded by trip duration, is the obvious shape but needs its own design pass
  (link expiry, rate limiting the public endpoint, what subset of `BookingVisibilityPolicy`-gated fields
  a non-user share link is even allowed to see).

Not designing either further now — flagging the sequencing decision as understood and queued.

## 13b. Decisions locked in (round 2) — supersedes §13's five questions

**(1) Fee-free rider cancel, generalized.** Not PIN-lockout-specific as §13 Q1 framed it — any
rider-initiated cancel tagged with a safety reason is fee-free, not just one caused by a PIN lockout.
Modeled as a new `RideFlag(RIDER_SAFETY_CANCEL, raised_by=RIDER)` row (not an `EligibilityReport` —
this isn't peer-reporting the driver for an eligibility violation, it's the rider declaring the ride
itself unsafe for them) plus a new `BookingService.cancelForSafetyConcern(customerUserId, bookingId,
reason)`, mirroring `cancelForEligibilityConcern`'s shape: fee-free up to a weekly-per-rider cap and a
per-rider/driver-pair cap (same two-cap shape as `wouldExceedDriverCancelCap`, mirrored for the rider
side rather than reused directly — different table, different subject). Beyond the cap, the cancel still
goes through but takes the normal fee, and the flag is marked cap-exceeded for priority review. PIN
lockout (§5) now routes through this same path rather than its own bespoke waiver.

**(2) GPS accuracy — mandatory, not best-effort.** §13 Q2 assumed a nullable field with silent
zero-credit fallback; superseded. `accuracyMeters` is documented as a required field in the OpenAPI
spec. It stays a plain (non-`@NotNull`) field on the DTO so one dev-only bypass can apply uniformly —
`DriverLocationService` rejects a missing value with a clear validation error unless
`ladygo.dev.allow_missing_location_accuracy=true`, a property that must never be true outside the
`dev`/`test` profiles. Enforced by a new startup guard (§13b-5) rather than trusted to just be
set correctly by convention.

**(3) Destination change — pre-`RIDE_STARTED` only, in scope now.** New endpoints, customer-side:
`POST /api/v1/customers/me/bookings/{id}/estimate-destination-change` (same shape as the existing
`estimate()` — no booking mutation) and `POST .../bookings/{id}/change-destination` body
`{ destinationLatitude, destinationLongitude, destinationAddress, expectedFare }`, allowed while status
∈ `{REQUESTED, SEARCHING_DRIVER, DRIVER_ASSIGNED, DRIVER_ACCEPTED, DRIVER_ARRIVING, DRIVER_ARRIVED}`
(anything strictly before `RIDE_STARTED`). Reuses `resolveCity`'s service-area check (throws
`OUT_OF_SERVICE_AREA` the same way `create()` already does) and the `checkFareTolerance`/
`FareChangedException` pattern for "rider confirms the new fare." Recomputes `estimatedDistanceM/
estimatedDurationS/estimatedFare/pricingRuleId` against the **currently active** pricing rule (not the
one recorded at original booking time — the route itself changed, so the old rule's applicability to
this trip did too). If a driver is already assigned (`driverId != null`), pushes them a
`RealtimePushService` notification. Mid-ride destination change stays deferred, exactly as drafted — the
rider's documented alternative is end-early (§4/§13b-1 scope, not safety-tagged unless it genuinely is)
plus a fresh booking.

**(4) Gate failure — fail closed, with a rider-confirm escape hatch.** Supersedes §13 Q4's framing (my
draft had no bypass at all). Stale/missing location at the `arrived` or `start` gate (§3) always fails
closed with a specific message (distinct text for "too stale" vs "too far" — never a generic failure).
New: once `DRIVER_ARRIVING` has been active for at least `ride.gate.rider_confirm_wait.seconds` (new
config, default 300) — measured from a new `Booking.driverArrivingAt` timestamp column, set at that
transition — the **rider** may call `POST /api/v1/customers/me/bookings/{id}/confirm-arrival`, which
transitions `DRIVER_ARRIVING → DRIVER_ARRIVED` as actor `CUSTOMER` (requires widening
`BookingStateMachine.authorise`'s customer-only-cancels rule — see §13b-6) and raises
`RideFlag(RIDER_CONFIRMED_ARRIVAL)` unconditionally, for review. This bypass is scoped to the `arrived`
gate only, per your own wording ("confirm arrival") — `start` has no equivalent bypass, since PIN
exchange already requires the two of them to be in physical contact, so a stuck location gate there is
resolved by the driver just sending a fresh location update, not by a rider override. Flagging that
narrower scope explicitly in case you intended it for `start` too.

**(5) `ONLINE` payment — rejected at booking creation, not just at completion.** Supersedes §13 Q5 — my
original draft only considered the post-ride `PAYMENT_PENDING` state; this decision reaches back to
`BookingService.create()` itself; `ONLINE` is rejected there with a new `ErrorCode.
PAYMENT_METHOD_UNAVAILABLE` (422), before any of this phase's cash-specific flow is ever reached. New
`PaymentStubGuard` (`ApplicationRunner`, `@Profile("staging | prod")` or an active-profile check) fails
application startup if any dev/test-only flag — `ladygo.payments.stub_enabled` (reserved for Phase 8,
unused today) or the `ladygo.dev.allow_missing_location_accuracy` flag from (2) — is `true` while the
active profile is `staging` or `prod`. One guard, both flags, since both are the same shape of risk
("a dev convenience must never leak into a real environment").

**(6) `BookingStateMachine.authorise` needs widening.** Not a round-2 decision itself, but required by
(1) and (4) above and not covered by the original design: today `authorise` forbids `CUSTOMER` from
transitioning to anything but `CANCELLED`. It needs to also permit `CUSTOMER → RIDE_COMPLETED` (early
end, §4/§13b-1) and `CUSTOMER → DRIVER_ARRIVED` (the confirm-arrival bypass, (4) above) — both already
constrained to the right `from` state by the existing `ALLOWED` map, so widening `authorise` only adds
these two specific targets, not a blanket loosening.

## 13. Open questions — superseded by §13b above, kept for the record

1. **PIN lockout fee waiver**: generalize the existing `cancelForEligibilityConcern`'s zero-fee cancel
   into a shared "fee-free cancel, reason X" path, or write a third near-duplicate method? Leaning
   toward generalizing but it touches an existing, already-tested method.
2. **`accuracyMeters` on `DriverLocationRequest`**: confirmed as a mobile API contract addition — do the
   Android/iOS driver apps already read GPS accuracy from the platform location API (they should, it's
   standard), or does this need a mobile-side change tracked separately before the backend gate can rely
   on it? If apps won't send it soon, the gate should treat missing accuracy as 0 credit (as designed
   above) rather than blocking on this.
3. **Rider-changed destination mid-ride**: in scope for this phase (needs a new endpoint + the fare-
   recalculation case in §6), or deferred to a later phase? Currently scoped out above.
4. **`ride.gate.max_location_age.seconds` = 30s default**: reasonable given the app's ~5s reporting
   cadence (docs/api/matching.md §3), but confirm — a driver who's just gone through a connectivity gap
   right at the pickup moment would be hard-blocked from "arrived" until a fresh point lands. Acceptable
   (driver app should just show "waiting for GPS" and retry), or should the gate fall back to something
   softer (e.g. allow with a flag, same as the completion check) when stale rather than reconnected?
5. **`ONLINE` payment method bookings sitting at `PAYMENT_PENDING` indefinitely** until Phase 8 exists —
   acceptable as a known gap for this phase (cash is D2's default anyway), or does this need a stub
   "mark paid" admin action in the meantime?

Stop here for approval before any code.
