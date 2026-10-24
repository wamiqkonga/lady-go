# Driver ride history, earnings, ratings and review back-fills

Status: **APPROVED 2026-10-24 (Wamiq).** Decisions: Q1 commission unset until the owner gives the rate; Q2 addresses shown, last 90 days; Q3 early end and cancellation earn 0 in v1; Q4 option A (safetyConcern creates a safety incident); Q5 rating window 7 days. Step 2 of `docs/development/plans/2026-10-23-android-public-launch-plan.md`.
Date: 2026-10-24. Money is always in minor units (paise) per ADR-007.

## 1. Why this exists (60-day adoption link)
Drivers stay on a platform that shows them what they earned and treats them fairly. Riders trust ratings. Step 3 (driver app history/earnings/ratings screens) and step 6 (customer rating screen) cannot be built without these endpoints. The slice 3 review also surfaced five backend gaps that put drivers and riders at risk; they are fixed here too.

## 2. What already exists (checked in the repo)
- `ratings` table (V9) with a unique `(ride_id, rater_id)`, score 1-5, comment, `rater_role`; entity and repository exist, **no service or controller**.
- `drivers.rating_avg` and `rating_count` columns exist and nothing updates them.
- `rides` (driver, customer, started/completed times, distance), `bookings.final_fare`, `bookings.cash_confirmed_at`, `payment_transactions`.
- `RideStatusSyncListener` already reacts to ride completion (releases the driver). It is the right hook for writing earnings.
- `safety_incidents` table (V10) with types SOS, REPORT_DRIVER, REPORT_CUSTOMER, OTHER and an admin queue shape.
- No commission or payout concept anywhere yet.

## 3. Scope
**In:** driver ride history; earnings summary (day/week/month) with commission snapshot per ride; ratings (both directions) and the driver rating summary; five back-fills (section 6).
**Out (later):** automated bank payouts (manual weekly payout from an admin report in step 8), tips, incentives, rider-visible driver comments, online payment settlement (step 5).

## 4. API (all under `/api/v1`, JWT required, response envelope as elsewhere)

### 4.1 Ride history (driver)
`GET /drivers/me/rides?page=0&size=20` returns newest first.
Item: `bookingId`, `rideId`, `status` (COMPLETED, CANCELLED, ENDED_EARLY), `completedAt`, `pickupAddress`, `destinationAddress`, `finalFare`, `currency`, `paymentMethod`, `myRating` (the score the driver gave, or null), `ratedByRider` (boolean, never the score: true only once both parties have rated or the rating window has closed, otherwise false, see 4.3). `pickupAddress` and `destinationAddress` follow the booking visibility policy: they are null for a CANCELLED booking.
Never returned: rider name, phone, selfie, exact coordinates. Only the caller's own rides (403/empty otherwise).

### 4.2 Earnings (driver)
`GET /drivers/me/earnings?from=2026-10-01&to=2026-10-07` (dates in the city's time zone; max 31 days; default current week).
Returns `totals` {rides, gross, commission, net, currency, cashCollected, onlineCollected, commissionConfigured} and `days[]` with the same fields per day.
Rule: a ride earns only when COMPLETED with a confirmed final fare. Early ends and cancellations earn 0 in v1 (open question Q3).

### 4.3 Ratings
- `POST /bookings/{bookingId}/rating` (rider rates driver) and `POST /drivers/me/bookings/{bookingId}/rating` (driver rates rider). Body: `score` 1-5, optional `comment` (max 500 chars, trimmed, no URLs checked server side beyond length), optional `tags[]` from a fixed list (e.g. POLITE, SAFE_DRIVING, CLEAN_VEHICLE, ON_TIME; for riders RESPECTFUL, ON_TIME).
- Rules: only the two people on that ride; only after status COMPLETED; once per rater (409 on repeat, idempotent for an identical resubmit); window 7 days after completion; `ratee` is derived server side, never taken from the request.
- Effect, in the same transaction: update `drivers.rating_avg`/`rating_count` (recompute from the ratings table for that driver, numeric(3,2), correct under concurrency). A score of 1 or 2 raises an admin ride flag for review (reuses `ride_flags`). A `REPEATED_LOW_RATING` flag (third 1-star from one rider to one driver in 7 days) is raised once: no new one while an unreviewed one exists for that pair in the window. Locking: status and window are checked first without locks; then the driver row, then the ride row are locked and refreshed.
- `GET /drivers/me/ratings/summary` returns `avg`, `count`, `distribution` (1-5) and `recentTagCounts` (last 10 ratings). To stop a driver inferring one rider's score, `distribution` and `recentTagCounts` are only filled when `count` >= 5; below that `distribution` still has keys "1" to "5" but all zero, `recentTagCounts` is `{}`, and `avg` and `count` are still returned. Unknown stored tag names are ignored. Comments are **not** shown to the driver in v1 (avoids retaliation); admins can read them.
- Rider rating of a driver is visible to that driver only as aggregates. Driver rating of a rider is never shown to riders.

## 5. Data model (one new Flyway migration, additive)
`V31__ride_earnings_and_rating_tags.sql`
- `ride_earnings`: `id`, `ride_id` unique, `driver_id`, `gross bigint`, `commission_bps int` (snapshot), `commission bigint`, `net bigint`, `currency`, `payment_method`, `earned_at`, `payout_batch_id uuid null`, `settled_at null`. Written exactly once when a ride completes (insert guarded by the unique ride id, so replays are harmless).
- `ratings.tags text[]`, check constraint on size.
- No change to existing columns. Old code ignores the new table, so rollback of the app does not need a schema rollback.
Commission: `commission = round_half_up(gross * bps / 10000)`, `net = gross - commission`. The rate comes from admin config key `earnings.commission.bps`. **If the key is unset, no commission is applied and `commissionConfigured=false`**, so the app shows "Commission not set" instead of a made-up number. The snapshot means changing the rate later never rewrites history.

## 6. Back-fills from the slice 3 review
| # | Change | Why |
|---|---|---|
| B1 | `GET /drivers/me/availability` returns OFFLINE/ONLINE/BUSY/ON_RIDE/SUSPENDED | The app can read the truth instead of guessing; removes the ghost-online class of bug |
| B2 | Accept offer is idempotent for the same driver: a repeat returns the booking (200) instead of an error | A lost response no longer orphans the ride |
| B3 | Driver booking response includes `cashConfirmedAt` | After an app kill the cash step shows the right state |
| B4 | Rider selfie delivered as a JPEG, longest side 1024 px, target under 300 KB | Real photos currently exceed the app's 2 MB cap (suspected, to be confirmed in a test with a 2000 px photo) |
| B5 | `safetyConcern` on driver early-end creates a `safety_incidents` row (type REPORT_CUSTOMER, severity HIGH, status OPEN, with ride and last location) and an audit entry | The checkbox currently does nothing. **Needs your decision, see Q4** |
Also: `findNearbyOnline` ignores drivers whose last location is older than a configurable age (`matching.driver.location.max.age.seconds`, default 120) so a ghost-online driver is never matched.

## 7. Security, privacy, abuse
- Every query is scoped by the authenticated driver or rider id taken from the JWT; ids in the path are checked for ownership (tests for another user's ride, expect 404 not 403 to avoid leaking existence).
- No rider PII in driver history. Ratings comments length-limited and stored as text, rendered escaped by clients; never put in logs.
- Rate limit rating submissions (10 per hour per user) to stop scripted bombing; scores of 1 from one rider against the same driver more than twice in 7 days raise a flag.
- Earnings and ratings reads are cached nowhere in v1 (simple correct SQL with the indexes below).
- Indexes: `ride_earnings(driver_id, earned_at desc)`, existing `ratings(ratee_id, created_at desc)`, `rides(driver_id, created_at desc)`.
- **Money flag:** earnings are display and payout records, not money movement. No money is moved by this step.

## 8. Tests (before code, per TDD)
Unit: commission rounding (including odd paise, zero, unset key), rating window and once-only rule, avg recompute, ownership checks. Integration (Testcontainers): migration applies, ride completion writes one earnings row even if the event is replayed, history pagination, earnings day buckets across the city time zone boundary, concurrent ratings keep avg correct, selfie JPEG size, idempotent accept, stale-location exclusion, early-end safety concern creates exactly one incident. `OpenApiSpecDriftTest` plus regenerated Kotlin clients for both apps.

## 9. Build order
1. Migration V31 and entities. 2. Earnings writer on completion + commission config. 3. History and earnings endpoints. 4. Rating service and endpoints, rating-driven flags. 5. B1-B5 and stale-location rule. 6. Regenerate `openapi.json` and both clients. 7. Update `docs/api/ride-lifecycle.md` and this doc status. Each is its own commit. Fresh-context review before closing the step.

## 10. Deploy and rollback
Additive migration, forward-only. Deploy to staging, run smoke (complete a cash ride, see earnings row, rate both ways), then production. Rollback: redeploy the previous image; the new table and column stay unused. If the earnings writer misbehaves, set `earnings.commission.bps` unset (no commission) and fix forward; history of rows is not destructive.

## 11. Open questions (need your answer before build)
- **Q1 Commission rate:** what percent does Lady Go keep per ride? (I will not guess; unset means gross only.)
- **Q2 Ride-history privacy:** show pickup/destination addresses in the driver's past rides (default yes, last 90 days) or area only?
- **Q3 Earnings on early end/cancel:** default earns 0. Do you want a cancellation or no-show fee to the driver in v1? (Needs a pricing rule; I recommend v2.)
- **Q4 `safetyConcern`:** (A) create a safety incident for ops (recommended, small), or (B) remove the checkbox from the driver app for now.
- **Q5 Rating window:** 7 days after completion (default) OK?

## 12. Web Admin tickets (paste into Web Admin; staging first)
**T10. Set the commission rate in staging config** - Why: earnings need the rate. Steps: after build, open admin web (staging), Config, add `earnings.commission.bps` with your rate in basis points (15 percent is 1500) and a reason; save. Acceptance: a completed test ride shows commission and net in the driver earnings; changing the value does not change old rides. Env: staging then production after UAT. Owner: you.
**T11. Verify stale-location rule setting** - Why: protects riders from matching to ghost drivers. Steps: confirm `matching.driver.location.max.age.seconds` is 120 in staging config. Acceptance: a driver whose location is 5 minutes old is not matched in a staging test. Owner: you.
