# Lady Go — Development Roadmap

Status: v0.7 · Phases 0–6 closed (auth, customer booking, driver onboarding, matching/offers); Phase 6.5
(ride lifecycle) and Phase 7.5 (trip sharing; SOS deferred) built, pilot scope only — see notes below;
WebSocket/Redis realtime infra (part of Phase 7) built, but live-tracking UI and admin live map not yet
started; closed-pilot client build order decided (Android first, iOS after). Since v0.6: admin web
(Phase 9) built for driver review, ride/flag oversight, audit log and config editing (live-map/drivers-
list/customers/safety screens still placeholder stubs); the driver-registration website is built;
the driver Android app has slices 1, 2a, 2b and 3 built (2b = STOMP ride-offer receive/accept/decline,
commit `a7091a7`; 3 = the ride loop, uncommitted, awaiting a fresh-context review and an emulator pass); the repo moved from a personal GitHub account to the `Lady-Go` GitHub organization, which
brought a CI minutes budget (Actions minutes are no longer effectively unlimited) — see the CI/security
hardening note below for what that changed about how CI is run.

## Phase plan

| Phase | Name | Main outcomes | Exit criteria |
|------:|------|---------------|---------------|
| 0 | Discovery | Requirements, architecture, DB design, roadmap | Docs reviewed; open decisions answered or defaulted |
| 1 | Project foundation (CLOSED 2026-10-03) | Monorepo, Spring Boot skeleton, React website + admin skeletons, Android/iOS skeletons (customer + driver), Docker Compose, CI skeleton | All projects build; CI green; health endpoint |
| 2 | Database (CLOSED 2026-10-04; backend CI green with Testcontainers) | Flyway migrations V1–V11, PostGIS, entities/repositories, ERD, Testcontainers | Migrations apply from empty DB; repository tests pass |
| 3 | Authentication (CLOSED 2026-10-04) | OTP, JWT, refresh, logout, RBAC, rate limiting, admin MFA | Security tests; auth E2E |
| 4 | Customer (BUILT; see note below) | Profile, saved locations, maps abstraction, fare estimate, booking creation/history | Booking API tests; app flows to booking |
| 5 | Driver (BUILT; see note below) | Onboarding, documents, verification, availability, offers | Admin approval path works end to end |
| 6 | Matching (BUILT 2026-10-07; see note below) | Nearby search, ranking, offers, timeouts, retry, cancellation handling | Concurrency tests; configurable rules |
| 6.5 | Ride lifecycle (BUILT 2026-10-09; inserted here, ahead of Payments; not in the original plan, see note below) | Arrive/PIN-start/complete/early-end, ride PIN flow, destination change, cash confirmation | Design in `docs/api/ride-lifecycle.md`, approved; adversarial review before closing |
| 7 | Real-time (PARTIAL — WebSocket/Redis infra built 2026-10-07; live tracking UI and admin live map not started) | WebSocket/STOMP, Redis, live tracking, admin live map | Load test at target scale |
| 7.5 | Trip sharing + Call 112 (BUILT 2026-10-09, pilot scope only — SOS deferred to a public-launch gate, see "Launch gates" below; not in the original plan, see note below) | Trip-share link (rider-only, hashed token, expiring/revocable), one-tap Call 112 | Design doc first, same as every other phase |
| 8 | Payments | Abstraction, provider, webhooks, refunds, receipts, cash flow | Idempotency and webhook tests |
| 9 | Admin portal (BUILT, see note below — step-up MFA still outstanding) | Dashboard, driver review/approval, ride/booking oversight, audit log, pricing/config editing. **Step-up MFA not yet built**: the config edit screen (`frontend/admin/src/pages/ConfigScreen.tsx`, `PUT /api/v1/admin/config/{id}`) exists and is live today, protected only by `SUPER_ADMIN` role + a mandatory free-text reason + an audit-log entry — **no fresh TOTP re-verification beyond login-time MFA**, confirmed against both the frontend save flow and the backend's `@PreAuthorize`/`AdminConfigService.update()`. This was deferred from Phase 3 (`docs/security/auth-design.md` §12, OQ-7) and is still deferred — do not ship this UI (i.e. do not expose it outside a developer's own machine) without it; see "Launch gates" below. | Admin E2E flows |
| 10 | Safety | SOS, emergency contacts, share trip, ride PIN, incidents | Incident E2E; ops runbook |
| 11 | Testing hardening | Coverage of critical flows, E2E, security review, load tests | Definition of Done met across modules |
| 12 | Deployment | Environments, containers, monitoring, backups, migration strategy | Staging mirrors production |
| 13 | App release | Signing, store metadata, policies, production config | Store submissions |

## Order change (decided, 2026-10-08) — supersedes the "suggested order tweak" below

Phases 4, 5 and 6 were built out of strict numeric order (customer booking, driver onboarding, and
matching/offers/WebSocket infra all shipped together as part of what the commit history calls "Phase
6"), closing CI-green along the way. Ride lifecycle (6.5) and SOS+trip-sharing (7.5) are inserted ahead
of Payments (8) because Payments needs a completed ride with a real final fare to charge against —
building it earlier would mean building it against data that doesn't exist yet. This is the rationale
the old "suggested order tweak" note below was gesturing at (ride PIN/trip-share pulled earlier for
safety); it's now a decided phase-list change, not just a note.

## Suggested order tweak (historical — see "Order change" above, which supersedes this)
Phase 10 (ride PIN, SOS basics) depends on the ride lifecycle (Phase 5). Because safety is a core differentiator, the ride PIN and trip-share endpoints may be pulled into Phase 5/7 and only the incident-management console left for Phase 10. This does not change the phase list; it is noted for planning.

## Branching and commits
`main` (releasable), `develop` (integration), `feature/*`. Conventional commits (`feat(auth): ...`). Small, focused commits.

## Definition of Done
Implementation, validation, authorisation, error handling, tests, passing build, updated docs and OpenAPI, no obvious security issues, Flyway migration for schema changes.

## Open decisions (owner: product/business)

| # | Decision | Needed by | Default if unanswered |
|---|----------|-----------|----------------------|
| D1 | Launch country/city | Phase 1 (config), Phase 3 (SMS) | India, single city |
| D2 | Payment provider and whether cash is allowed at launch | Phase 8 | Cash + one regional online provider behind the abstraction |
| D3 | SMS/OTP provider and registration requirements | Phase 3 | **Provider-agnostic `SmsSender` adapter built** (`docs/development/sms-provider-setup.md`) - generic HTTP adapter configured entirely from `LADYGO_SMS_*` env vars, dev/test uses `LoggingSmsSender`. **Pilot channel decided 2026-10-17: WhatsApp via Meta Cloud API instead of SMS** (no DLT; config in the WhatsApp section of that doc). Pilot blocker is Meta business verification + an approved authentication template, owner: founder. SMS/DLT is only a public-launch fallback. |
| D4 | Maps provider (Google Maps vs alternatives) and pricing | Phase 4 | **DECIDED 2026-10-03: Google Maps Platform behind `MapsService` (see ADR-011).** Still run the local data-quality check on 50–100 Srinagar points early in Phase 4 and measure real API calls per ride. |
| D5 | Rider and driver eligibility (gender policy, children, companions, male drivers) | Phase 4/5 | **DECIDED 2026-10-05: no Aadhaar; drivers = licence + live selfie + phone + vehicle registration + self-declaration; riders = phone + live selfie + self-declaration; eligibility rests on human admin review (selfie matched to licence photo for drivers) plus the self-declaration — no government gender source (residual risk). See `docs/api/driver-onboarding.md`.** Companions/minors sub-question unchanged: accompanied minors allowed (placeholder `checkCompanions` behaviour). |
| D6 | Vehicle types at launch | Phase 4 | Cab only |
| D7 | Cancellation and no-show policy, fees | Phase 4 | Free grace window, then flat fee (configurable) |
| D8 | Commission/service-fee model and driver payout approach | Phase 8 | Percentage service fee, manual payouts |
| D9 | SOS operating model (24x7 team, who contacts police/emergency services) | Phase 10 | Admin queue + emergency contact SMS; ops procedure to be defined |
| D10 | Background-verification approach and required documents | Phase 5 | **DECIDED 2026-10-05, as part of D5**: manual admin document/selfie review — see `docs/api/driver-onboarding.md`. Open sub-questions remain: RC photo vs number only, insurance, police verification — listed in that doc's open questions. |
| D11 | Data retention periods and legal/regulatory review (data protection, transport licensing) | Phase 2/12 | Proposed periods in database-design.md |
| D12 | Cloud/hosting target | Phase 12 | Containers on a managed cloud provider |
| D13 | App languages | Phase 4 | English first; localisation-ready strings |
| D14 | Brand assets, app names, bundle IDs | Phase 1 | Placeholder IDs (`com.ladygo.customer`, `com.ladygo.driver`) |
| D15 | Android/iOS minimum versions | Phase 1 | Android API 26+, iOS 16+ |

## Security backlog (from docs/security/auth-design.md §15)

Two Phase 3 findings were accepted as tradeoffs for now, not fixed immediately — tracked here so they have a concrete trigger instead of being forgotten:

| Item | Trigger | What lands |
|---|---|---|
| Refresh-token grace window | Before the mobile apps reach real users (Phase 11 Testing hardening at the latest, ideally before any Phase 13 beta build) | A bounded ~10s grace window so a dropped-connection retry of an already-rotated refresh token isn't treated as theft, provided the successor token is still unused. |
| Admin login lockout hardening | Before production (Phase 12 Deployment at the latest) | Replace the hard per-(email, IP) lockout with progressive delay, failure alerting, and a `SUPER_ADMIN` unlock path. |

## Launch gates

Distinguishes what's needed for a **closed pilot** (small, known user set, operationally supervised)
from what's a **hard gate before any public launch**. Added because SOS is being deferred past the
pilot (decided below) and that decision needs a concrete, written trigger — the same reasoning as the
"Security backlog" table above, applied to product/safety gaps rather than just security ones.

| Item | Required for | Why |
|---|---|---|
| SOS (docs/api/safety.md) | **Public launch** — not the pilot | Deferred past the closed pilot: a small, known rider/driver set with direct operational supervision (see the pilot plan) is an acceptable substitute for automated SOS at that scale, but is not an acceptable substitute once the platform has riders/drivers the ops team doesn't already know personally. Trip sharing + a one-tap "Call 112" button (native dialer, no platform involvement) ship in the pilot as the interim safety affordance — see docs/api/safety.md's status line. |
| Real OTP provider (D3) - WhatsApp for the pilot | Pilot (WhatsApp) / public launch (SMS fallback) | `LoggingSmsSender` is dev/test-only by construction (`SmsSenderStartupGuard`); the generic `HttpSmsSender` adapter is built (`docs/development/sms-provider-setup.md`) but nothing is configured yet. Decided 2026-10-17: the pilot sends OTPs over WhatsApp (Meta Cloud API, no DLT), so the pilot is *not* exempt - it needs Meta business verification, an approved authentication template and the `LADYGO_SMS_*` env vars before real users can log in. SMS (and its TRAI DLT registration) is only needed as a public-launch fallback for users without WhatsApp. |
| Real payment provider / `ONLINE` payment method (D2, D8) | Public launch | Currently rejected outright at booking creation (`PaymentMethodUnavailableException`) — the pilot is cash-only by design (see the pilot plan), not as a limitation discovered late. |
| Background-verification process actually run on every driver (D5/D10) | Both — already a hard gate even for the pilot | Not a launch-scale question; a women-only safety platform cannot pilot with unverified drivers under any framing. |
| `docs/security/auth-design.md` §15 backlog (table above) | Public launch, both items | Already tracked there with the same phase triggers; listed here only so "launch gates" is the one place that answers "are we ready yet." |
| Admin-web production CSP header, specifically `frame-ancestors` (`docs/security/admin-web-csp.md`) | Pilot or public launch, whichever first exposes admin web outside a developer's own machine | The dev-server header and the build-time `<meta>` tag ship automatically with the code, but `frame-ancestors` cannot be expressed in a `<meta>` tag at all (CSP spec) - it only works as a real HTTP header, which has to be configured at whatever reverse proxy/CDN actually serves the built static files. Nothing in this repo can set it for a static host, so it is a deploy-time step, not a code gate. |
| Step-up MFA on destructive admin actions (Phase 9 table above, `docs/security/auth-design.md` §12 OQ-7) | Pilot or public launch, whichever first exposes admin web outside a developer's own machine | The `SUPER_ADMIN` config edit screen is built and live today (`ConfigScreen.tsx` / `AdminConfigController`), protected only by role + a mandatory reason + an audit-log entry - no fresh TOTP re-verification. Admin user management is the same gap once/if it ships. Do not expose the admin web beyond a developer's own machine until this is built. |

## Risk register
See `docs/product/requirements.md` section 7.

## Phase 1 status notes (2026-10-03)

- Website and admin: verified (lint, type-check, tests, build).
- Backend: Spring Boot 3.5.7 skeleton (health, standard response envelope, central exception handling, request-ID filter, stateless security with strict CORS, OpenAPI, profiles, tests). Not compiled yet: first `mvn verify` may require bumping versions in `pom.xml`.
- Android (customer, driver): Compose + MVVM skeleton, Gradle version catalog. Not built; library versions must be refreshed on first build.
- iOS (customer, driver): SwiftUI + MVVM skeleton generated through XcodeGen (`project.yml`). Not built.
- Dependencies are added only when first used (database, Redis, JWT, Hilt/Retrofit, Maps in later phases).
- Exit criteria for closing Phase 1: CI green for backend, Android and iOS jobs; fix anything it finds.

## Phase 1 closure (2026-10-03)

Backend, Android and iOS CI jobs went green after two workflow fixes (dynamic iOS simulator selection; use the runner's preinstalled Android SDK instead of `android-actions/setup-android`).

## Phase 2 status notes (2026-10-03)

- Migrations V1–V11 were applied from an empty PostgreSQL 16 + PostGIS database on the authoring machine and the key invariants were exercised with SQL (see `docs/database/erd.md`).
- Entities (27), enums (29) and repositories are written but **not compiled by Maven here** (Maven Central is blocked on the authoring machine); only a syntax check was possible. Integration tests need Docker (Testcontainers) and first run in CI.
- Exit criteria for closing Phase 2: backend CI green including `DatabaseMigrationTest`, `EnumSchemaParityTest`, `PersistenceMappingTest`, `DatabaseConstraintsTest`.
- Likely first-run issues to expect: Hibernate schema validation of geography/jsonb/char columns, Testcontainers/Docker API version, Docker Hub pull limits.

## Phase 2 closure (2026-10-04)

`mvn -B verify` passed clean on the first CI run: no Hibernate schema mismatches, no Testcontainers/Docker issues, no enum/migration drift. All four exit-criteria test classes green (`DatabaseMigrationTest` 6, `EnumSchemaParityTest` 1, `PersistenceMappingTest` 9, `DatabaseConstraintsTest` 19), 54 tests total in the module, 0 failures. Confirmed in the Backend GitHub Actions run (not just local) that Testcontainers pulled `postgis/postgis:16-3.4` and exercised the real database. Backend and Security workflows both green on `develop`.

## Phase 3 closure (2026-10-04)

OTP login (CUSTOMER/DRIVER), admin email+password+TOTP with forced enrolment, rotating refresh tokens with reuse detection, database-backed rate limiting, and a client-IP resolver — design in `docs/security/auth-design.md`, approved before implementation. A fresh adversarial-review agent (no session context, same checklist the project owner specified) found 1 CRITICAL, 2 HIGH and 2 MEDIUM real issues; all but the 2 MEDIUM tradeoffs were fixed with a failing-first regression test each (132 backend tests total, 0 failures). The 2 accepted tradeoffs and OQ-8 are decided and tracked above and in `docs/security/auth-design.md` §13/§15. PR #1 (develop→main) green across Backend/Android/iOS/Security from actual logs, not merged (awaiting owner review). `docs/development/auth-local-runbook.md` written and personally walked end to end against a local stack.

## Phases 4–6 status (2026-10-07) — built together, not in strict numeric order

Customer booking (fare estimate, creation, history, cancellation), driver onboarding/verification,
availability, and matching/offers/WebSocket+Redis realtime infra were implemented and adversarially
reviewed across several commits culminating in `60b367a`/`bd21632` on `develop`. A central
`BookingVisibilityPolicy` (allow-listed fields per viewer role/status) and an ArchUnit rule banning
`Enum.ordinal()` comparisons were added after a fresh-context review found a real ordinal-comparison
bug admitting cancelled bookings into a detail view. Driver location staleness is judged from server
receive time, not the device clock (`docs/api/matching.md` §3), with a documented, accepted residual
risk around device-delta chaining. Backend and Security CI green on every commit in this range.

## Phase 6.5 status (started 2026-10-08)

Design in `docs/api/ride-lifecycle.md`, approved with decisions on fee-free safety cancels, mandatory
GPS accuracy, pre-start destination change, a rider-confirm-arrival gate bypass, and rejecting `ONLINE`
payment until Phase 8. Implemented, adversarially reviewed (7 automated findings, each fixed with a
regression test or proven a false positive), early-end fare policy corrected per the initiator×progress
table. A follow-up root-cause audit (2026-10-09, prompted by the trip-share finding below) found
`Ride.status` was only ever written on two of the transitions that affect it, and
`DriverRepository.releaseFromOffer` was never called after a ride actually concluded — both fixed by
`RideStatusSyncListener` (see `docs/api/ride-lifecycle.md` §1 "Root-cause fix"). Backend and Security CI
green throughout.

## Phase 7.5 status (started 2026-10-09, pilot scope only — trip sharing + Call 112; SOS deferred)

Design in `docs/api/safety.md`. Trip sharing implemented: rider-only creation, 256-bit hashed/
constant-time-compared token, configurable post-ride-end expiry, revocation, active-link cap, narrow
public response (driver first name/vehicle/plate/live location only), per-IP and per-token rate limits,
identical error for every failure mode, no-store headers. A fresh-context adversarial review found a
CRITICAL finding (a cancelled booking's stale `Ride.status` let a trip-share link keep leaking live
location after the ride was actually over) — fixed, then root-caused per the Phase 6.5 note above. "Call
112" is a native-dialer button client-side only, no backend involvement. SOS itself stays deferred past
the pilot per the Launch gates table.

## OpenAPI spec (2026-10-09)

`docs/api/openapi.json` is checked in (springdoc-generated, canonicalised for a readable diff).
`OpenApiSpecDriftTest` fails `mvn verify` — and therefore the existing Backend CI job, no new workflow
needed — if the live spec no longer matches the checked-in file.

## Closed-pilot client build order (decided 2026-10-09) — Android first, iOS after

Per the approved closed-pilot plan (`docs/product/pilot-plan.md`): cohort is 5–10 drivers and 30–50
known riders, one area, 2–4 weeks, daytime only, founder-monitored via the admin console, cash only, no
SOS. Build order:

1. OpenAPI contract checked into CI (done above) — every client below is generated/validated against it.
2. Admin web (`frontend/admin`) — the founder's own monitoring tool; needed before any pilot user touches
   the app at all.
3. Driver Android app (`mobile/android-driver`).
4. Customer Android app (`mobile/android-customer`).
5. Public trip-tracking page (website phase) — polls `~`8s with backoff when the tab is hidden.

iOS (`mobile/ios-driver`, `mobile/ios-customer`) starts only after the Android-only pilot concludes —
the cohort size (5–10 drivers, 30–50 riders) doesn't justify building and maintaining two native stacks
before the first real-world signal comes back.

## Phase 9 (admin web) status (2026-10-17)

Built: `Dashboard`, `Login`, driver review/approval (`DriverDocumentQueue` + per-driver `DriverReview`),
ride/booking oversight with flag handling (`RideOversight` + `RideDetail`), an audit log viewer
(`AuditLog`), and the config/pricing editor (`ConfigScreen`) - all real screens, confirmed against
`frontend/admin/src/pages/` and the routes in `App.tsx`, not placeholders. Still placeholder stubs
(routed to a generic `Placeholder` component, not built): live map, a plain drivers list, a customers
screen, and a safety screen. **Config editing has no step-up MFA yet** - see the Phase 9 table row and
"Launch gates" above; this is the one concrete, currently-shippable-but-unsafe-to-expose gap in what's
built so far.

## Driver-registration website status

Built, not a skeleton: OTP login, a dashboard, document upload (with file validation/preview and
resumable upload state), and selfie capture, each with their own tests, plus a Playwright e2e suite
(`frontend/driver-registration/e2e/`). `frontend/driver-registration/src` is ~5,700 lines across 21
files, confirmed non-trivial.

## Driver Android app status

Slice 1 (auth/OTP login, onboarding status gate, generated OpenAPI client) and slice 2a (go-online
toggle, location foreground service, battery-optimisation guidance) are done, each closed with its own
fresh-context review pass (see commit history: `d0db4d8`, `a938c4e`). Slice 2b (STOMP-over-WebSocket
connection manager with reconnect/backoff/token-refresh, ride-offer receive/accept/decline, a
high-priority offer notification, and reconciliation on reconnect/resume) is done (`a7091a7`, with a
fresh-context review that found and fixed 3 CRITICAL + 4 IMPORTANT issues) - design in
`docs/product/driver-android-design.md`. Slice 3 (the ride loop: to pickup, arrived + rider photo, PIN, in trip, complete + cash confirm,
early end, Call 112, resume after app kill) is built and unit-tested (design in
`docs/product/driver-android-design.md` §7) but has not yet had a fresh-context review or been run
against the real backend on an emulator; no ride-history screen yet (no endpoint).

## CI/security hardening status (2026-10-17)

The repo's primary GitHub remote moved from a personal account to the `Lady-Go` GitHub organization
(confirmed: `origin` now points at `github.com/Lady-Go/lady-go.git`; an old personal-account remote is
still present locally under a stale name but is not what CI/PRs use). This is not just cosmetic: an
organization's GitHub Actions minutes are a real, metered budget in a way a personal account's
effectively weren't, which is why this repo's working agreement since the move has been one CI run per
work slice (mid-slice backup pushes use `[skip ci]`; only the final push of a slice runs CI) rather than
a push-per-commit habit.

Separately, a full Semgrep SAST triage closed out all 42 findings from the first real scan (SHA-pinning
every mutable GitHub Actions tag, a 7-day Dependabot/npm release-age cooldown, two false-positive
suppressions for the apps' required `MAIN`/`LAUNCHER` activities) and added a real severity gate to the
`sast` CI job: it now fails on `ERROR`/`HIGH`/`CRITICAL` findings and only reports (non-blocking) below
that, with no `continue-on-error` masking the gate itself - see `docs/security/sast-triage.md`. The org move
also broke the `secret-scan` job (`gitleaks-action` needs a paid licence for an organization-owned repo);
fixed 2026-10-17 by running the MIT-licensed gitleaks CLI directly (pinned release, SHA-256 verified,
full-history scan, `--redact`) instead of the action - no licence or secret needed; a local scan of all
103 commits found no leaks. Still open: the PR-only `dependency-review` job fails because the dependency
graph / GitHub Advanced Security is not available for this private org repo.
