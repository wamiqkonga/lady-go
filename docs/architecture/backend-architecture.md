# Lady Go — Backend Architecture

Status: Draft v0.1 · Phase 0

## 1. Package layout

```text
com.ladygo
├── common/          ApiResponse, exceptions, ids, time, money, pagination, MDC filter
├── config/          Spring config, properties binding, OpenAPI, Redis, WebSocket
├── security/        JWT filter, method security, rate limiting, password/OTP hashing
├── auth/            OTP, login, refresh tokens, sessions
├── users/           User aggregate, roles, status
├── customers/       Customer profile, saved locations, emergency contacts
├── drivers/         Driver profile, documents, verification, availability
├── vehicles/        Vehicles and vehicle verification
├── bookings/        Booking aggregate, state machine, cancellation policy, events
├── rides/           Ride lifecycle (start/complete), ride PIN, timeline
├── matching/        Candidate search, ranking, offer lifecycle (part of rides/bookings boundary; own package)
├── locations/       Location ingest, live state, history sampling, retention
├── pricing/         Fare calculator, pricing rules, surge (post-MVP)
├── payments/        PaymentService, provider SPI, transactions, webhooks, refunds
├── notifications/   Event listeners, templates, channel SPI (push/SMS/email)
├── ratings/         Ratings and aggregates
├── promotions/      (post-MVP)
├── support/         Issues, safety incidents, SOS, trip sharing
├── admin/           Admin-facing controllers/services, audit, configuration
└── analytics/       Read models and reports (post-MVP)
```

`matching` is added to the recommended structure because the matching algorithm has its own lifecycle and configuration; this is a documented deviation from the list in the master prompt.

Each module has the internal layering:

```text
web (controllers, request/response DTOs)
  -> service (use cases, transactions, authorisation checks)
    -> domain (entities, state machines, policies)
      -> repository (Spring Data JPA, native PostGIS queries)
```

Rules enforced by ArchUnit tests (Phase 1/2):
- Controllers do not touch repositories.
- JPA entities never appear in controller signatures.
- Modules interact via public service interfaces or domain events, not each other's repositories.
- No hard-coded business values; read via `ConfigurationService`.

## 2. API conventions

- Base path `/api/v1/...`, JSON, UUID identifiers.
- Envelope:

```json
{ "success": true, "data": {}, "message": null, "timestamp": "2026-10-03T12:00:00Z" }
```

- Errors: `{ "success": false, "error": { "code": "BOOKING_INVALID_STATE", "message": "..." }, "timestamp": "...", "requestId": "..." }`.
- Pagination: `page`, `size`, `sort`; response includes totals.
- Idempotency: `Idempotency-Key` header on booking creation and payment initiation.
- Status codes: 200/201/204, 400 validation, 401, 403, 404, 409 state conflict, 422 business rule, 429 rate limit, 5xx sanitised.

### Exceptions
`ResourceNotFoundException`, `UnauthorizedException`, `ForbiddenException`, `ValidationException`, `BookingStateException`, `PaymentException`, `DriverUnavailableException`, mapped centrally; no stack traces to clients.

## 3. Major API modules (initial surface)

| Module | Sample endpoints |
|--------|------------------|
| auth | `POST /auth/otp/request`, `/auth/otp/verify`, `/auth/refresh`, `/auth/logout`, `POST /auth/admin/login` |
| customers | `GET/PUT /customers/me`, `/customers/me/saved-locations`, `/customers/me/emergency-contacts`, `/customers/me/rides` |
| drivers | `POST /drivers/onboarding`, `/drivers/me/documents`, `PUT /drivers/me/availability`, `GET /drivers/me/earnings`, `GET /drivers/me/rides` |
| bookings | `POST /bookings/estimate`, `POST /bookings`, `GET /bookings/{id}`, `POST /bookings/{id}/cancel` |
| rides | `POST /rides/{id}/arrived`, `/rides/{id}/start` (PIN), `/rides/{id}/complete`, `POST /rides/offers/{offerId}/accept|reject` |
| payments | `POST /payments`, `GET /payments/{id}`, `POST /payments/webhooks/{provider}`, `POST /payments/{id}/refund` |
| locations | `POST /locations/driver` (HTTP fallback), WS destinations for updates |
| notifications | `POST /notifications/devices` (push token register), `GET /notifications` |
| support | `POST /support/sos`, `POST /support/reports`, `POST /rides/{id}/share` |
| admin | `/admin/dashboard`, `/admin/drivers/**`, `/admin/customers/**`, `/admin/rides/**`, `/admin/pricing`, `/admin/config`, `/admin/audit`, `/admin/incidents` |

## 4. Booking and ride state machine

Implemented as an explicit transition table (enum + allowed-transition map, one `BookingStateMachine` service). Every transition: validates current state with pessimistic/optimistic locking, authorises the actor, persists a `booking_events` row, publishes a domain event. Invalid transitions raise `BookingStateException` (HTTP 409).

## 5. Matching service

```text
BookingCreated -> MatchingService.start(booking)
  loop until accepted or max search time:
    candidates = DriverGeoIndex.nearby(pickup, radius)      // Redis GEO
    filter: ONLINE, approved, vehicle type match, not already offered
    rank:   strategy chain (distance, rating, acceptance rate) via RankingStrategy beans
    offer:  atomic claim of driver (Redis SET NX with TTL = response timeout)
    wait:   accept | reject | timeout
  on accept -> DRIVER_ACCEPTED; on exhaustion -> CANCELLED(NO_DRIVER_FOUND)
```

Configuration keys: `driver.search.radius`, `driver.response.timeout`, `ride.max.search.time`, `matching.max.offers`, `matching.ranking.weights`. Ranking is a strategy interface so smarter algorithms can be introduced later.

## 6. Pricing

`FareCalculator` reads versioned `pricing_rules` (per city and vehicle type): base fare, per km, per minute, minimum fare, service fee, cancellation fee, surge multiplier (post-MVP). Estimates store the rule version used; final fare is computed server-side from actual distance/duration. Money in minor units.

## 7. Provider SPIs

```java
interface PaymentProvider { PaymentIntent initiate(...); PaymentStatus verify(...); Refund refund(...); WebhookEvent parseWebhook(...); }
interface MapsService { geocode(); reverseGeocode(); calculateRoute(); calculateDistance(); calculateETA(); searchPlaces(); }
interface NotificationChannel { void send(NotificationMessage m); }  // PushChannel, SmsChannel, EmailChannel
interface FileStorage { store(); signedUrl(); delete(); }
```

Each has a mock/fake implementation for dev and tests.

### Maps: Google Maps Platform (ADR-011) and cost controls

`GoogleMapsService` is the first `MapsService` implementation. Server-side API keys stay in the backend; mobile and web map keys are restricted by app ID / referrer and carry only map-display permissions.

Cost controls built into `MapsService` (from the Phase 0 estimate of roughly 19 billable calls per completed ride, about $0.08/ride beyond the free tier):
- Use Autocomplete session tokens so autocomplete requests inside a session are not billed individually; debounce typing.
- Prefer the map pin as the source of truth for pickup; avoid reverse-geocoding on every pin movement (geocode when the pin settles).
- Cache geocode results and saved/popular pickup points where the licence allows.
- Cache route estimates for identical origin/destination within a short window; compute estimates once per ride type request, not per keystroke.
- Use straight-line (PostGIS/Redis GEO) distance for driver ranking; call Routes only for the assigned driver's ETA and the fare estimate.
- Limit live ETA refresh frequency.
- Emit per-SKU call counters as metrics and configure Google Cloud budget alerts and quota caps.

## 8. Data access

- Spring Data JPA for aggregates; native queries or Hibernate Spatial for PostGIS (`ST_DWithin`, `ST_Distance`) with GiST indexes.
- Flyway migrations only (`V1__...`); `ddl-auto=validate`.
- Optimistic locking (`@Version`) on bookings, rides, drivers.
- Testcontainers (PostGIS, Redis) for repository/integration tests.

## 9. Testing strategy (backend)

Unit (domain, state machine, fare), service tests, repository tests (Testcontainers), API tests (MockMvc/RestAssured), security tests (authorisation matrix per endpoint), WebSocket tests, concurrency tests for driver claiming, contract tests for provider adapters. CI gates on lint (Checkstyle/Spotless), static analysis (SpotBugs), dependency check, and coverage thresholds for critical modules.

## 10. Observability hooks

Micrometer metrics from the start (booking counts by state, match latency, offer outcomes, location update rate, payment failures, notification failures). Actuator health/readiness endpoints; request-ID filter; structured JSON logging (Logback + logstash encoder).
