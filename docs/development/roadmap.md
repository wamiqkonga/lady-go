# Lady Go — Development Roadmap

Status: Draft v0.1 · Phase 0

## Phase plan

| Phase | Name | Main outcomes | Exit criteria |
|------:|------|---------------|---------------|
| 0 | Discovery | Requirements, architecture, DB design, roadmap | Docs reviewed; open decisions answered or defaulted |
| 1 | Project foundation (WRITTEN 2026-10-03; backend/Android/iOS awaiting first real build) | Monorepo, Spring Boot skeleton, React website + admin skeletons, Android/iOS skeletons (customer + driver), Docker Compose, CI skeleton | All projects build; CI green; health endpoint |
| 2 | Database (WRITTEN 2026-10-03; migrations verified on PostGIS 16 locally, Java/Testcontainers tests awaiting first CI run) | Flyway migrations V1–V11, PostGIS, entities/repositories, ERD, Testcontainers | Migrations apply from empty DB; repository tests pass |
| 3 | Authentication | OTP, JWT, refresh, logout, RBAC, rate limiting, admin MFA | Security tests; auth E2E |
| 4 | Customer | Profile, saved locations, maps abstraction, fare estimate, booking creation/history | Booking API tests; app flows to booking |
| 5 | Driver | Onboarding, documents, verification, availability, offers, ride lifecycle | Admin approval path works end to end |
| 6 | Matching | Nearby search, ranking, offers, timeouts, retry, cancellation handling | Concurrency tests; configurable rules |
| 7 | Real-time | WebSocket/STOMP, Redis, live tracking, admin live map | Load test at target scale |
| 8 | Payments | Abstraction, provider, webhooks, refunds, receipts, cash flow | Idempotency and webhook tests |
| 9 | Admin portal | Dashboard, management screens, pricing/config, audit | Admin E2E flows |
| 10 | Safety | SOS, emergency contacts, share trip, ride PIN, incidents | Incident E2E; ops runbook |
| 11 | Testing hardening | Coverage of critical flows, E2E, security review, load tests | Definition of Done met across modules |
| 12 | Deployment | Environments, containers, monitoring, backups, migration strategy | Staging mirrors production |
| 13 | App release | Signing, store metadata, policies, production config | Store submissions |

## Suggested order tweak (for confirmation)
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
| D3 | SMS/OTP provider and registration requirements | Phase 3 | Provider abstraction + mock in dev |
| D4 | Maps provider (Google Maps vs alternatives) and pricing | Phase 4 | **DECIDED 2026-10-03: Google Maps Platform behind `MapsService` (see ADR-011).** Still run the local data-quality check on 50–100 Srinagar points early in Phase 4 and measure real API calls per ride. |
| D5 | Rider and driver eligibility (gender policy, children, companions, male drivers) | Phase 4/5 | Women riders and women drivers; accompanied minors allowed |
| D6 | Vehicle types at launch | Phase 4 | Cab only |
| D7 | Cancellation and no-show policy, fees | Phase 4 | Free grace window, then flat fee (configurable) |
| D8 | Commission/service-fee model and driver payout approach | Phase 8 | Percentage service fee, manual payouts |
| D9 | SOS operating model (24x7 team, who contacts police/emergency services) | Phase 10 | Admin queue + emergency contact SMS; ops procedure to be defined |
| D10 | Background-verification approach and required documents | Phase 5 | Manual document review |
| D11 | Data retention periods and legal/regulatory review (data protection, transport licensing) | Phase 2/12 | Proposed periods in database-design.md |
| D12 | Cloud/hosting target | Phase 12 | Containers on a managed cloud provider |
| D13 | App languages | Phase 4 | English first; localisation-ready strings |
| D14 | Brand assets, app names, bundle IDs | Phase 1 | Placeholder IDs (`com.ladygo.customer`, `com.ladygo.driver`) |
| D15 | Android/iOS minimum versions | Phase 1 | Android API 26+, iOS 16+ |

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
