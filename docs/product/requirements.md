# Lady Go — Product Requirements (Phase 0)

Status: Draft v0.1 · Phase 0 (Discovery) · Last updated: 2026-10-03

## 1. Vision

Lady Go is a women-focused pick-and-drop transportation platform. Safety, trust and privacy are core product features, not add-ons. The system starts as a small local service and must be able to grow into a multi-city platform without a rewrite.

## 2. Assumptions (working, to be confirmed)

| # | Assumption | Impact if wrong |
|---|-----------|-----------------|
| A1 | Launch market is a single city in India (working assumption: Srinagar, J&K). | Payment provider, SMS/OTP provider, maps coverage, KYC documents and legal compliance change. |
| A2 | Riders are women (and possibly children accompanied by them); drivers are women. | Driver supply is the main business risk; policy on male drivers / male riders changes onboarding and safety design. |
| A3 | Vehicle types at launch: cab (4-seater) and possibly auto/bike. | Pricing and matching rules per vehicle type. |
| A4 | Payments at launch: cash plus one online provider. | Cash handling affects driver earnings, settlement and fraud flows. |
| A5 | Small team; one backend monolith (modular) is preferable to microservices. | See architecture docs. |

## 3. Actors

- **Customer (Rider)** — books and takes rides.
- **Driver** — completes onboarding, goes online, accepts rides.
- **Admin / Super Admin / Support** — operate the platform. Roles are enforced server-side.
- **Emergency contact** — receives shared-trip links and SOS alerts (not a registered user).

## 4. Scope

### 4.1 MVP (must ship for first paid pilot)

**Identity and access**
- Phone-number registration with OTP, login, logout, refresh tokens, role-based authorisation.

**Customer**
- Profile (name, photo, emergency contact), saved locations, ride history.
- Pickup/destination selection on a map, fare estimate (distance, duration, fare), ride-type selection.
- Booking creation, driver search status, driver details (name, photo, vehicle, plate, rating), live driver tracking, arrival notification.
- Ride PIN verification at pickup.
- Cancel booking (with configurable fee policy), rating, receipt.
- Share trip link with emergency contact; SOS button.

**Driver**
- Registration, document upload (government ID, licence, vehicle registration, vehicle info, photo).
- Verification status; cannot go online until approved.
- Online/offline, receive ride request with timed accept/reject, navigate to pickup (hand-off to external navigation app), mark arrived, start (PIN), complete.
- Earnings summary and ride history.

**Matching**
- Configurable nearby-driver search, ranking, response timeout, retry to next candidate, overall search timeout.

**Payments**
- Payment abstraction; cash plus one online provider; server-verified payment confirmation; webhook handling; idempotency; receipts.

**Admin portal**
- Dashboard counts, driver verification queue (approve/reject with reason), customers and drivers lists with suspend/activate, rides list/detail, live map of drivers and active rides, pricing and business-configuration screen, SOS/incident queue, audit log viewer.

**Platform**
- Notifications (push + SMS) through abstraction; maps abstraction; audit logging; structured logging; CI pipeline; dev/staging/prod configuration.

**Public website**
- Home, about, how-it-works, safety, driver (recruitment), contact, faq, terms, privacy, login.

### 4.2 Post-MVP

- Scheduled rides, ride-sharing/pooling, multi-stop trips, round trips, rental/hourly packages.
- Surge pricing rules engine, promotions/coupons/referrals, wallet, driver incentives.
- Advanced matching (acceptance-rate/rating weighting, ETA by road routing, batching).
- Support ticket system with chat, in-app calling with number masking, in-app audio recording.
- Background-verification vendor integration, periodic re-verification.
- Multi-city/multi-region configuration, multi-language (including Urdu, Kashmiri, Hindi), accessibility options.
- Analytics and reporting, revenue reports, driver payouts/settlement automation.
- Route-deviation detection, trip-anomaly alerts, safety scoring.
- Email notifications and receipts, saved payment methods/cards.
- Rider preferences (e.g. preferred driver, quiet ride).

## 5. Functional requirements (summary)

IDs are stable and used for traceability in later phases.

| ID | Requirement | Phase |
|----|-------------|-------|
| FR-AUTH-1 | Register/login with phone + OTP; OTP expiry, attempt limits, resend throttling | MVP |
| FR-AUTH-2 | Short-lived JWT access tokens, rotating refresh tokens, logout revokes refresh token | MVP |
| FR-AUTH-3 | Roles CUSTOMER, DRIVER, ADMIN, SUPER_ADMIN, SUPPORT enforced server-side | MVP |
| FR-CUS-1 | Profile, emergency contact, saved locations, ride history | MVP |
| FR-BKG-1 | Fare estimate before booking using configurable pricing | MVP |
| FR-BKG-2 | Booking state machine rejects invalid transitions | MVP |
| FR-BKG-3 | Customer and driver cancellation with reason and policy-based fee | MVP |
| FR-DRV-1 | Driver onboarding with document upload and admin approval | MVP |
| FR-DRV-2 | Driver states OFFLINE, ONLINE, BUSY, ON_RIDE, SUSPENDED | MVP |
| FR-MAT-1 | Matching uses configurable radius, timeout, max search time | MVP |
| FR-RT-1 | Customer sees live driver position during assigned ride | MVP |
| FR-RT-2 | Admin sees live drivers and active rides on a map | MVP |
| FR-PAY-1 | Payment success confirmed server-side (webhook/verification), never client-only | MVP |
| FR-PAY-2 | Idempotent payment initiation and webhook processing | MVP |
| FR-SAF-1 | SOS creates a safety incident, notifies admin and emergency contact | MVP |
| FR-SAF-2 | Share-trip link, time-limited, revocable | MVP |
| FR-SAF-3 | Ride PIN verified before ride start | MVP |
| FR-ADM-1 | Admin actions are audit-logged | MVP |
| FR-ADM-2 | Business configuration editable (pricing, radius, timeout, fees) with audit | MVP (basic) |
| FR-PRV-1 | Historical location access restricted, audited, retention-limited | MVP |

## 6. Non-functional requirements

- **Security:** OWASP ASVS L2 as target; no secrets in repo; authorisation on every protected endpoint; UUID public identifiers.
- **Privacy:** Location data minimisation; configurable retention; encrypted at rest/in transit; admin access to history requires specific permission and is audited.
- **Performance (initial targets):** p95 API latency < 300 ms for non-map endpoints; location update ingestion handles 2,000 active drivers at ~1 update / 4–5 s; matching begins within 2 s of booking.
- **Availability:** 99.5% for pilot; graceful degradation when maps/SMS/payment providers fail.
- **Scalability:** Stateless backend instances; Redis for shared real-time state; DB schema supports `city_id` from the beginning.
- **Observability:** Structured logs with request/booking/ride/driver IDs; metrics-ready; no sensitive values in logs.
- **Maintainability:** Modular monolith, DTO boundaries, Flyway migrations, automated tests per Definition of Done.
- **Compatibility:** Android 8+ (API 26+), iOS 16+, evergreen browsers.

## 7. Technical risks

| Risk | Severity | Mitigation |
|------|----------|------------|
| Driver supply (women drivers) is limited | High (business) | Recruitment flow on website, incentive design post-MVP; decision on policy needed. |
| Connectivity in target market is unreliable | High | Retry/offline-tolerant location batching, short timeouts, resumable state from server on reconnect. |
| SMS/OTP delivery and regulatory registration (e.g. DLT in India) | High | Choose provider early; abstraction; fallback channel. |
| Background battery use from driver location | Medium | Adaptive update intervals, only while online. |
| Real-time scaling and WebSocket fan-out | Medium | Redis pub/sub, stateless nodes, topic-level authorisation. |
| Location privacy / regulatory exposure | High | Retention limits, access control, audits; legal review. |
| Payment provider constraints, cash settlement fraud | Medium | Provider abstraction; reconciliation jobs. |
| Maps cost and coverage | Medium | MapsService abstraction, caching of geocodes/routes where licence allows. |
| Four native apps multiply effort | High | Shared API contract (OpenAPI), generated clients, strict MVP scope. Consider shared design system. |
| SOS response is operational, not just technical | High | Define 24x7 response process before launch. |
| Mapping/geo accuracy and region-specific place data | Medium | Pilot validation of geocoding quality early. |

## 8. Decisions requiring business clarification

See the "Questions requiring business decisions" section in the Phase 0 report and `docs/development/roadmap.md` (Open decisions). Key blockers: launch city/country, payment provider, SMS provider, maps provider, driver and rider eligibility rules, cash-payment policy, cancellation policy, SOS operations, and legal/compliance (data protection and transport licensing).

## 9. Engineering decisions made (to be recorded as ADRs)

| ID | Decision | Rationale |
|----|----------|-----------|
| ADR-001 | Modular monolith (single Spring Boot deployable) with strict package boundaries | Small team; avoid distributed-system cost; modules can be split later. |
| ADR-002 | Gradle (Kotlin DSL) or Maven — default **Maven** | Widely supported, simpler for CI; revisit in Phase 1 if team prefers Gradle. |
| ADR-003 | Opaque public IDs are UUIDs; internal PKs are UUID v7 where supported or UUID v4 | Avoids enumeration. |
| ADR-004 | Live driver state in Redis (GEO + hash with TTL); PostgreSQL stores sampled history | Meets "don't store every second" rule. |
| ADR-005 | STOMP over WebSocket with Redis pub/sub relay for horizontal scaling | Spec suggestion; fits Spring. |
| ADR-006 | Provider abstractions: `PaymentProvider`, `NotificationChannel`, `MapsService`, `SmsGateway`, `FileStorage` | Replaceable vendors. |
| ADR-007 | Money stored as integer minor units plus ISO currency | Avoids floating-point errors. |
| ADR-008 | Business rules live in a `configuration` table (cached), with defaults in YAML | No hard-coded pricing/radius/timeouts. |
| ADR-009 | Document files stored in private object storage with short-lived signed URLs | Secure document access. |
| ADR-010 | Phone number is the primary login identifier for customers and drivers; admins use email + password + MFA | Matches spec; admin accounts need stronger controls. |
| ADR-011 | Maps provider is Google Maps Platform. Mobile apps render maps with the native Google Maps SDK (free for mobile map display). Places (autocomplete, details), Geocoding and Routes are called only through the backend `MapsService`. | Strongest place search, no servers to run, free monthly allowance (10,000 calls per SKU) covers roughly the first ~1,000 rides/month on our estimates. Provider stays replaceable through the abstraction. Pricing was checked 2026-10-03 and must be re-checked before launch. |
