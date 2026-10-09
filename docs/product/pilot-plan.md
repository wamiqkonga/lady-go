# Closed pilot — minimum-cut plan

Status: **approved 2026-10-09** (all 4 open questions below answered). Supersedes the open-question
framing in §9 - kept in the doc as a record of what was asked and decided, not as live questions
anymore.

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
- **Real SMS/OTP provider, OTP only** (decided 2026-10-09 - this was an open question, now resolved):
  `MockSmsService` is dev/test-only by construction (`SmsStartupGuard` enforces this can never run in
  staging/prod), and a closed pilot still needs real OTP delivery - unlike SOS, there's no "supervise
  around it" substitute for login itself. Trip-sharing is unaffected (it already avoids any SMS
  dependency via the OS share sheet). Provider options to document before pilot start as an
  operational prerequisite - not a backend code change beyond swapping the `SmsService`
  implementation, since the provider abstraction already exists:
    - **MSG91**, **Twilio Verify**, or an India-specific aggregator such as **Gupshup**/**Kaleyra** -
      any of these work; the real constraint is regulatory, not technical (next bullet).
    - **TRAI DLT registration is the actual blocker, not provider choice**: any transactional/OTP SMS
      sent to an Indian number requires the sender's entity, header (sender ID), and message template
      to be pre-registered on the DLT (Distributed Ledger Technology) platform the telecom operators
      run (via each provider's DLT portal, e.g. the Airtel/Jio/Vodafone shared DLT registries). This is
      a multi-day process requiring a registered business entity, GST details, and template
      pre-approval matching the exact OTP message text - it cannot be done the week before launch.
      **Action item, owner: founder** - start DLT registration as soon as a provider is chosen,
      independent of any further backend work.

## 3. Explicitly deferred past the pilot (Launch gates)

Already tracked in `docs/development/roadmap.md`; restated here because this plan is what makes the
deferral concrete rather than abstract:

- **SOS** (automated incident creation, admin alerting, emergency-contact SMS) - docs/api/safety.md §§1-3.
  Trip-share + Call 112 are the interim affordance.
- **`ONLINE` payment method** - already rejected outright at booking creation
  (`PaymentMethodUnavailableException`). The pilot is **cash-only** by design, not by an undiscovered
  gap. Matches D2's default (cash + one online provider) partially - the online half is simply not
  built yet, deliberately sequenced after the pilot.

## 4. Client requirements (mobile apps)

New, on top of everything Phase 4-6.5 already specified:

- **"Call 112" button** (§2) - both customer and driver apps, visible during an active ride
  (`DRIVER_ACCEPTED` through `RIDE_STARTED`, same lifecycle window trip-sharing is relevant for).
- **Trip-share action** - triggers `POST /api/v1/bookings/{id}/trip-shares`, then hands the resulting
  link to the OS share sheet (`Intent.ACTION_SEND` / `UIActivityViewController`) rather than the app
  picking a channel itself.
- **"Manage active shares" screen, minimal** (decided 2026-10-09 - in scope, not deferred): a plain list
  of the current ride's active trip-share links (created-at, expires-at) with a revoke action per link.
  No link metadata beyond that - no recipient name/channel (the backend never captures who a link was
  shared with, by design: the OS share sheet hand-off means the app genuinely doesn't know).
  Auto-revoke all of a ride's active links when the ride ends.
  **Two small backend additions this needs, neither built yet** (flagging now since they weren't in
  the original trip-share spec; no code written for either):
    1. `GET /api/v1/bookings/{id}/trip-shares` (rider-only, same ownership check as create/revoke) -
       there is currently a create and a revoke endpoint but no list, so the UI has nothing to render
       the screen from today.
    2. An `onRideCompleted`-style hook that revokes every still-active share for a ride the moment it
       completes, rather than relying on the client to be open and call revoke itself at that moment
       (unreliable - the rider may have backgrounded the app). The backend's post-ride-grace-then-404
       behavior already makes a stale, un-revoked link harmless from a security standpoint, so this is
       a UX honesty fix ("this is now off" shown promptly) layered on top of an already-safe default,
       not a security requirement in itself.

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
  **every ~8s, with backoff when the page/tab is hidden** (decided 2026-10-09, resolving the earlier
  10-15s proposal). 8s uses roughly 7 of the 10-requests/60s per-token rate limit budget, leaving
  headroom for a page reload or a brief foreground/background flap. "Backoff when hidden" means: stop
  polling entirely on the Page Visibility API's `hidden` state (phone locked, tab backgrounded) rather
  than continuing to burn the rate-limit budget on a view nobody's looking at, and resume at the normal
  8s cadence immediately on becoming visible again (not a gradual ramp - the window for "stale on
  return" should be as short as the first request's round trip, not a multi-step backoff curve working
  its way back down).
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

**Cohort, duration and oversight (decided 2026-10-09)**:
- **5-10 drivers, 30-50 known riders**, one area (the "one city" framing in §1 is actually one area
  within a city - small enough that every participant is personally known to the ops team, not just
  city-wide known).
- **2-4 weeks** running duration.
- **Daytime operating hours only** - narrows the "ops team reachable" requirement above to specific
  hours rather than 24/7, and is itself part of the SOS-deferral risk tradeoff (no rides running during
  the hours an incident would be hardest to respond to without automated SOS).
- **Founder personally monitoring the admin console** for the duration - this is the actual
  operational substitute for SOS at this scale, not just "ops team reachable by phone" in the abstract;
  see the admin-web design doc for what that monitoring screen set looks like.
- **Lawyer to confirm closed-pilot legality and insurance** before pilot start - a hard precondition,
  not a nice-to-have: running paid rides with real money, even in a small known cohort, has regulatory
  and liability exposure (local transport/aggregator licensing, driver/vehicle insurance adequacy for
  commercial passenger transport) that needs sign-off independent of anything this document can
  resolve. **Action item, owner: founder**, same urgency as the DLT registration above - both are
  external, lead-time-bound prerequisites, not backend work.

## 7. Preconditions before pilot start (owner: founder, both lead-time-bound)

1. DLT registration completed for the chosen SMS provider (§2) - cannot be compressed into the week
   before launch.
2. Lawyer confirmation of closed-pilot legality and insurance adequacy (§6).

## 8. Exit criteria — when the pilot can graduate past this scope

Tied directly to `docs/development/roadmap.md`'s Launch gates table (the real SMS provider from §2 is
a pilot **precondition**, already satisfied by the time the pilot runs, not listed again here):
1. SOS built and live.
2. Real payment provider / `ONLINE` method live (or a deliberate decision to stay cash-only longer).
3. The pilot's own operational log (§6) reviewed with no unresolved safety gaps the deferred features
   would have caught.

## 9. Decisions record (was: open questions — all four answered 2026-10-09)

1. Real SMS provider integration - **in scope**, OTP only; provider options and DLT registration
   documented in §2.
2. "Manage active trip-shares" client UI - **in scope**, minimal (list + revoke, auto-revoke at ride
   end); see §4 for the two small backend additions it needs.
3. Public tracking page polling cadence - **~8s with backoff when hidden** (§5), not the originally
   proposed 10-15s.
4. Pilot cohort size/duration/oversight - **5-10 drivers, 30-50 known riders, one area, 2-4 weeks,
   daytime hours only, founder monitoring the admin console, lawyer sign-off required before start**
   (§6).

Client build order for the pilot (OpenAPI contract → admin web → driver Android → customer Android →
public tracking page; iOS after) is tracked in `docs/development/roadmap.md`, not restated here.
