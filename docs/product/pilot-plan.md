# Closed pilot — minimum-cut plan

Status: design draft, no code yet. Design-first per instruction; stop for approval before any further
work driven by this plan.

Supersedes one line of `docs/product/requirements.md` §4.1's MVP list - that list includes "SOS button"
as must-ship for the first paid pilot. Per the decision already recorded in
`docs/development/roadmap.md`'s "Launch gates" section, SOS is deferred past the pilot; trip sharing and
a one-tap "Call 112" button are the pilot's safety affordance instead. Everything else in that MVP list
is unaffected by this plan unless called out below.

## 1. What "closed pilot" means here

A small, **known** set of riders and drivers (not public signup) in a single city, run with direct
operational supervision - the ops team can reach every participant another way (phone call) if
something goes wrong, which is exactly the condition `docs/development/roadmap.md`'s Launch gates
section uses to justify deferring SOS past this stage. This is not a soft-launch-to-the-public; it's
closer to a supervised field test with real money and real rides.

## 2. In scope for the pilot

Everything already built and CI-green on `develop`:

- Identity: OTP login/registration (CUSTOMER/DRIVER), JWT + refresh tokens (Phase 3).
- Customer: profile, saved locations, fare estimate, booking creation/history, cancellation
  (Phase 4).
- Driver: onboarding, document/selfie verification (admin-reviewed, manual - not automated, per D5/D10),
  vehicle registration, availability (Phase 5).
- Matching: nearby search, offers, accept/reject, timeouts (Phase 6).
- Ride lifecycle: arrive/PIN-start/complete/early-end, destination change pre-start, cash confirmation +
  dispute window (Phase 6.5).
- **Trip sharing** (docs/api/safety.md §4, just implemented): rider-generated link, shared via the
  device's native share sheet (no SMS dependency - the rider's own phone sends it however they like:
  message, WhatsApp, email), public unauthenticated tracking view.
- **"Call 112" button** (new client requirement, this plan): a plain native-dialer button in both
  mobile apps - `tel:112` (or the equivalent platform intent/URL scheme). No backend involvement at
  all: no API call, no logging of when it's pressed, no platform liability framing of an automated or
  assisted dial. It just opens the phone's own dialer pre-filled, the same as if the rider had dialled
  it herself. Placed visibly during an active ride (same screen the trip-share action lives on).

## 3. Explicitly deferred past the pilot (Launch gates)

Already tracked in `docs/development/roadmap.md`; restated here because this plan is what makes the
deferral concrete rather than abstract:

- **SOS** (automated incident creation, admin alerting, emergency-contact SMS) - docs/api/safety.md §§1-3.
  Trip-share + Call 112 are the interim affordance.
- **`ONLINE` payment method** - already rejected outright at booking creation
  (`PaymentMethodUnavailableException`). The pilot is **cash-only** by design, not by an undiscovered
  gap. Matches D2's default (cash + one online provider) partially - the online half is simply not
  built yet, deliberately sequenced after the pilot.
- **Real SMS/OTP provider** - `MockSmsService` is dev/test-only by construction
  (`SmsStartupGuard` enforces this can never run in staging/prod). For a closed pilot with a small,
  **known** cohort the ops team onboards by hand, this needs resolving before pilot start regardless -
  unlike SOS, there's no "supervise around it" substitute for OTP login itself. **Open question**:
  is a minimal real SMS provider integration in scope for THIS plan, or is it being tracked as its own
  separate piece of work? Flagging rather than assuming - this plan's scope is the SOS/trip-share/
  Call-112 decision specifically, not a full re-scope of every pilot dependency.

## 4. Client requirements (mobile apps)

New, on top of everything Phase 4-6.5 already specified:

- **"Call 112" button** (§2) - both customer and driver apps, visible during an active ride
  (`DRIVER_ACCEPTED` through `RIDE_STARTED`, same lifecycle window trip-sharing is relevant for).
- **Trip-share action** - triggers `POST /api/v1/bookings/{id}/trip-shares`, then hands the resulting
  link to the OS share sheet (`Intent.ACTION_SEND` / `UIActivityViewController`) rather than the app
  picking a channel itself. A "manage active shares" affordance (list + revoke) is implied by the
  backend already supporting revoke and a cap of 3 active links per ride, but isn't explicitly speced
  here - **open question**: in scope for this pilot, or is "create one link, let it expire" good enough
  for a first cohort?

## 5. Website scope addition

Per instruction, the **public tracking page** (what a `GET /api/v1/trip-shares/lookup` link actually
renders for the person who receives it) belongs to the website, not the mobile apps - the recipient is
by definition not someone with the app installed. Scope for that page, when the website phase reaches
it:

- Reads the token from ITS OWN URL (e.g. `ladygo.app/track/{token}`) client-side, then calls the backend
  lookup endpoint with the token in the `X-Trip-Share-Token` header (never re-exposing it in a second
  URL/query string at the website's own backend-call layer).
  Renders: driver first name, vehicle, plate, a live location on a map. Nothing else - matches the
  backend response shape exactly (`TripShareLookupResponse`), so the page has no PII to accidentally
  over-render even if someone tried.
- No login, no account creation, no app-install prompt gating the view itself (that would defeat the
  point of a shareable safety link) - at most a non-blocking "get the app" banner.
- Auto-refreshes the location (polling the lookup endpoint, respecting its own `Cache-Control: no-store`)
  at a reasonable interval - **open question**: polling cadence, proposing every 10-15s matching the
  driver's own ~5s location-report cadence closely enough to feel live without hammering the per-token
  rate limit (10 requests/60s - a 10s poll interval uses 6 of that budget, leaving headroom).
- Shows a clear, distinct message once the link has expired/been revoked/the ride ended - the backend's
  identical-error-for-everything design is correct for the API (no information leakage), but the
  page's OWN copy can and should still say something human ("this link is no longer active") rather than
  a raw error.

## 6. Operational requirements during the pilot

- Every driver's background verification actually completed (admin review) before they're allowed into
  the pilot cohort - already a hard requirement regardless of pilot/launch per Launch gates, not new
  here.
- Ops team reachable during all pilot operating hours, with each pilot participant's real phone number
  on file outside the app (the substitute for SOS - see §1).
- A lightweight incident log (even a spreadsheet, not necessarily `safety_incidents` since that table's
  service layer isn't built yet - SOS is deferred) for anything that happens during the pilot, so there's
  a real record to evaluate "was deferring SOS the right call" against before scaling past this cohort.

## 7. Exit criteria — when the pilot can graduate past this scope

Tied directly to `docs/development/roadmap.md`'s Launch gates table:
1. SOS built and live.
2. Real SMS/OTP provider configured.
3. Real payment provider / `ONLINE` method live (or a deliberate decision to stay cash-only longer).
4. The pilot's own operational log (§6) reviewed with no unresolved safety gaps the deferred features
   would have caught.

## 8. Open questions — stop here for your decision

1. Real SMS provider integration - in this plan's scope, or tracked separately? (§3)
2. "Manage active trip-shares" client UI - in scope for the pilot, or defer to "create and let it
   expire"? (§4)
3. Public tracking page polling cadence - proposing 10-15s (§5), confirm or adjust.
4. Pilot cohort size/duration and launch city - this plan assumes Srinagar (A1's working assumption)
   and says nothing about numbers; is that decided elsewhere already, or does this plan need to state
   a concrete size/duration to be useful as an actual plan rather than a scope list?

Stop here for approval before any further work driven by this plan.
