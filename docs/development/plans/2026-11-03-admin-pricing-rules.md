# Plan: admin Pricing rules (set and change the fares from the portal)

Status: **DRAFT, waiting for the owner's approval. No code has been written.**
Date: 2026-11-03. One PR into `develop` after approval. Nothing goes to `main` until the owner says so (merging to `main`
deploys production).

How to read this: every term is explained the first time it appears. Section 11 lists the questions only the owner can
answer; each has a recommended default, so "yes to all" is a valid reply.

---

## 1. Goal and the 60-day number

**Goal.** Let the owner (a SUPER_ADMIN) see the fare riders are charged, and change it safely, without touching the
database.

**Why this blocks launch.** A rider's price estimate and the final fare both come from one row in a database table
(`pricing_rules`: one row per city and vehicle type). If there is no row, the app cannot give an estimate and nobody can
book. Today nothing in the portal can create or change that row. The existing "Pricing" screen edits general settings
(search radius, timeouts) and not fares. Without this work the Srinagar two-wheeler fare can only be set by a hand-written
database command, which is exactly the kind of high-risk production change this project avoids.

**Your success rule (people using it within 60 days).** A wrong fare puts riders off or loses captains money, so the
measures are:

| # | Measure | Source |
|---|---|---|
| 1 | Riders who got an estimate and then booked | bookings already stored |
| 2 | Rides completed per week | rides already stored |
| 3 | Cash disputes per 100 rides (rider or captain says the fare was wrong) | `cash_disputed_at` already stored |
| 4 | Number of fare changes and who made them | audit log (built here) |

No new tracking tools. The Dashboard session already adds measure 2; measure 3 is one query (ticket P-6).

## 2. Users and their problem

One user: you (SUPER_ADMIN). Problem: you must set the first fare before launch and adjust it during the pilot as you
learn what riders accept and what captains need. A typing slip (an extra zero) must not reach riders.

## 3. Scope

**In scope**
1. List the current fare and the full history of fares for a city and vehicle type.
2. Create a new fare version, effective now or at a chosen future moment, with a mandatory reason.
3. A safety check against typing slips and a live "what a rider would pay" preview, old against new.
4. Cancel a change that is scheduled for the future and has not taken effect.
5. Everything audited, SUPER_ADMIN only, step-up authenticator code on every write.

**Out of scope (and why)**
- Surge pricing (post-MVP in your own flow spec; the `surge_config` column stays empty).
- Creating or editing **cities** and their service-area map. The pilot has one city. This plan only shows whether the
  city exists and has a service area, so a missing one is spotted before launch (question Q6).
- Vehicle types other than two-wheelers: riders can only book two-wheelers now, so only BIKE rows are shown unless one
  already exists for another type.
- Payments and refunds (pilot is cash only).
- Changing a fare for a ride already booked: never allowed, see 4a.

## 4. Design

### 4a. The rules that keep money safe
- **Versioned, never edited.** The table's own comment says a change creates a new row and closes the previous one
  (`effective_to`). Each booking remembers which row it was priced with (`bookings.pricing_rule_id`), and an early-ended
  ride is also priced with that remembered row. So **a fare change never alters a booking already made.** Riders mid-booking
  keep the price they were shown. This is the key property and gets its own test.
- **No gaps, no overlaps.** The new row starts exactly when the old one ends. The database already forbids two rows for the
  same city and vehicle type overlapping in time (`ex_pricing_rules_no_overlap`); the code closes the old row and inserts the
  new one in **one transaction** and locks the current row first, so two admins pressing Save together cannot both win.
- **Money in whole paise (minor units).** Same as the rest of the product (ADR-007). The screen takes rupees (up to two
  decimals), converts to paise exactly, and refuses anything that is not a whole number of paise.

### 4b. Typing-slip protection
- **Absolute limits** per field, enforced on the server (recommended defaults, all editable by you later through the
  existing settings screen): base fare up to Rs 500, per km up to Rs 100, per minute up to Rs 20, minimum fare up to
  Rs 1,000, cancellation fee up to Rs 500, service fee up to 30 percent and Rs 100 flat. Zero is allowed (for example no
  service fee).
- **Large-change guard.** If any field moves by more than **50 percent** from the current value, the server refuses unless
  the request says "I confirm this large change", and the screen shows a red warning with the old and new numbers.
- **Preview before saving.** The screen shows sample fares (for example 2 km / 8 min, 5 km / 15 min, 10 km / 30 min)
  under the current and the new fare side by side, calculated by the same `FareCalculator` riders' estimates use, so what
  you see is what a rider is charged.
- **Confirmation step** shows old to new for every field, the effective time and your reason, and asks for the
  authenticator code (step-up).

### 4c. Scheduling
A new fare can take effect **now** or at a **future time** (not in the past). Only one future change can be waiting at a
time. To change a scheduled one you cancel it first. Cancelling is allowed only while it has not taken effect and no booking
refers to it (it is then removed by its own id, with a check that exactly one row was affected). Recommend a minimum
lead of 0 minutes (you may want "now").

### 4d. API (all under `/api/v1/admin`, SUPER_ADMIN only)
| Endpoint | What it does |
|---|---|
| `GET /pricing-rules?cityId=&vehicleType=` | Current fare and history, newest first; also whether a change is scheduled |
| `GET /pricing-rules/cities` | Cities with a flag for "service area set" and which vehicle types have a fare |
| `POST /pricing-rules/preview` | Sample fares for given values and distances; changes nothing |
| `POST /pricing-rules` | Create the new version (needs reason, step-up, and the confirm flag for a large change) |
| `POST /pricing-rules/{id}/cancel` | Cancel a scheduled change |

Every write records an audit entry `PRICING_RULE_CREATED` or `PRICING_RULE_CANCELLED` with actor, role, IP, reason and the
old and new values. Failures return the same error format as the rest of the admin API.

### 4e. Screen
The existing **Pricing** menu item becomes two tabs: **Fares** (new) and **Settings** (the current screen, unchanged).
Fares shows a card for each vehicle type with the current numbers in rupees, "in force since", any scheduled change, a
**Change fare** form, and the history table. At phone width the history becomes cards. If no fare exists yet for the city,
the card says "No fare set: riders cannot book" in red and offers **Set the first fare**.

### 4f. Database
**No migration expected**: the table, its overlap rule and the audit table already exist. If a build step finds it needs
one (for example an index), it is additive and takes the next free number after checking `origin/develop`.

## 5. Security and compliance

- Server enforces role (SUPER_ADMIN only; ADMIN and SUPPORT are refused), step-up on both writes, a mandatory reason,
  and every limit. The screen is convenience only.
- Rows are never updated except closing the old one's `effective_to`; a bounded delete exists only for a future,
  unreferenced rule and is checked to affect exactly one row. No other UPDATE or DELETE is introduced.
- No secrets, no personal data. The audit entry holds numbers and a reason, nothing about riders.
- Because this is money, the PR gets `/security-review` and `/code-review` before merge (your rule for money features).

## 6. Build steps (small slices; a slice is done only after a real-browser run)

| Step | Work | Model |
|---|---|---|
| 1 | Failing backend tests for each rule in 7 | Sonnet 5.5 |
| 2 | Service and endpoints (list, preview, create, cancel), audit, limits, guard | Sonnet 5.5 |
| 3 | Regenerate the API spec, web types and both Android clients | Sonnet 5.5 |
| 4 | Admin web Fares tab, tests, Playwright flow | Sonnet 5.5 |
| 5 | Real-browser run at desktop and 375 px: first fare, change, large-change warning, scheduled change, cancel | Sonnet 5.5 |
| 6 | Fresh-context `/security-review` and `/code-review`, fix findings | Opus 5.5 |

## 7. Test steps

Backend, written first and run as committed (non-transactional) tests wherever commit timing or concurrency matters:
only SUPER_ADMIN can read or write (ADMIN, SUPPORT, rider and captain tokens refused); no step-up gives
`STEP_UP_REQUIRED`; missing reason gives 400; every absolute limit; large change refused without the flag and accepted with
it; the old row closes exactly when the new one opens (no gap, no overlap); two simultaneous creates for the same city and
vehicle type give exactly one winner; a booking made before the change keeps its old fare and rule, and an early-ended ride
still uses the booking's rule; a booking made after uses the new one; preview equals what the real calculator returns;
scheduled change takes effect at its time and cannot be created twice; cancel works only for a future, unreferenced rule;
audit rows hold old and new values and the reason.
Frontend: component tests for each state. End to end: set the first fare, change it with a large-change confirmation,
schedule and cancel.
Manual: phone width; wrong authenticator code; a rupee value with three decimals.

## 8. Deploy and rollback

New endpoints are additive. Deploy the backend, then the admin web (it calls the new endpoints). Rolling back the web or
the backend leaves the fares as they are. A wrong fare is corrected by creating another version; history is kept. If the
portal is unavailable, the break-glass route is a documented SQL change run on the server by you (close the current row,
insert the new one) written as a runbook step, never improvised.

## 9. HIGH-RISK items

| # | Risk | Level | Mitigation |
|---|---|---|---|
| R1 | A wrong fare reaches riders (typo, extra zero) | HIGH | Limits, large-change guard, preview, confirmation, step-up, reason, audit |
| R2 | Two admins change at once, leaving a gap or overlap | MEDIUM | One transaction, row lock, database overlap rule, concurrency test |
| R3 | A change alters a booking already made | HIGH | Versioned rows plus per-booking rule, explicit tests |
| R4 | A rider sees a different estimate right at the switch moment | LOW | The existing fare tolerance check asks the app to refresh the estimate; noted in the release text |
| R5 | No fare exists at launch, so nobody can book | HIGH | Red "No fare set" state; ticket P-2 before launch |
| R6 | Unbounded UPDATE/DELETE, hard-coded values, production data change | NONE planned | Bounded statements only; no data change on production by me |

## 10. Web Admin tickets (drafted for you to paste and run; Claude cannot run these)

**P-1. Decide the first two-wheeler fare for Srinagar.** Env: none. Why: riders cannot book without it, and I will not
invent your prices. Steps: decide base fare, per km, per minute, minimum fare, cancellation fee and service fee
(percent or flat), all in rupees. Accept: the six numbers written down. Owner: you.

**P-2. Set the fare on staging, then check it from a rider's phone.** Env: staging first. Steps: after the Fares tab ships
to staging, sign in as SUPER_ADMIN, open Pricing, Fares, **Set the first fare**, enter the numbers from P-1, preview, save
with the authenticator code. In the rider app request an estimate for a known route. Accept: the estimate equals the
preview for that distance and time; an audit-log entry exists. Owner: you.

**P-3. Set the fare on production.** Same steps after the production deploy. Accept: same, plus no ride is affected.
Owner: you.

**P-4. Confirm the Srinagar city and its service area exist on production.** Steps: open the Fares tab: the city list
shows "service area set". Accept: Srinagar present with a service area. If not, tell me; creating cities is out of scope
and needs its own small plan. Owner: you.

**P-5. Real-device estimate check.** Steps: with the production fare set, request an estimate in the real rider app.
Accept: the number matches what the screen's preview gave for that route. Owner: you.

**P-6. Save the dispute-rate query.** I supply one saved query (cash disputes per 100 completed rides); you run it weekly.
Owner: you.

## 11. Questions only the owner can answer (defaults in brackets)

- **Q1.** The six first-fare numbers (ticket P-1). No default: I will not invent prices.
- **Q2.** Large-change guard at 50 percent? [yes]
- **Q3.** The absolute limits in 4b? [yes, adjustable later]
- **Q4.** Only SUPER_ADMIN can change fares (as in your flow spec); ADMIN can view? [ADMIN and SUPPORT cannot even view]
- **Q5.** Allow future-dated changes? [yes, one at a time]
- **Q6.** Do you want a small separate plan for creating and editing cities and the service area after launch? [not now]
- **Q7.** Show only two-wheeler fares unless another type already has a row? [yes]
