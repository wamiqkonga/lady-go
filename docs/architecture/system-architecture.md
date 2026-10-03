# Lady Go — System Architecture

Status: Draft v0.1 · Phase 0

## 1. Context

```text
 Customer App (Android/iOS)   Driver App (Android/iOS)   Website   Admin Portal
            \                        |                      |           /
             \------- HTTPS REST + WebSocket (STOMP) -------/----------/
                                     |
                         API Gateway / Load balancer (TLS)
                                     |
                     Spring Boot backend (modular monolith)
                 /        |          |           |           \
          PostgreSQL    Redis    Object storage   Providers (maps, SMS,
          + PostGIS    (live      (documents,     push, payments)
                        state)     photos)
```

## 2. Components

| Component | Tech | Responsibility |
|-----------|------|----------------|
| Backend | Java 21, Spring Boot 3 | REST API, WebSocket, business logic, scheduling, provider integrations |
| PostgreSQL + PostGIS | PG 16 + PostGIS 3 | Source of truth: users, bookings, rides, payments, audit, sampled location history |
| Redis | Redis 7 | Live driver location (GEO), driver status, offer locks/timeouts, rate limiting, OTP attempt counters, pub/sub for WebSocket fan-out, config cache |
| Object storage | S3-compatible | KYC documents and photos, private bucket, signed URLs |
| Customer / Driver apps | Kotlin+Compose, Swift+SwiftUI | Native clients on a shared API contract |
| Website | React + TypeScript (Vite) | Public marketing/informational site |
| Admin portal | React + TypeScript (Vite) | Operations UI, live map |
| Provider adapters | Java interfaces | `PaymentProvider`, `MapsService`, `NotificationChannel` (push/SMS/email), `FileStorage` |

## 3. Key architecture decisions

1. **Modular monolith.** One deployable, packages per bounded context under `com.ladygo`, dependencies only through module service interfaces and domain events. Reasoning: low operational cost, easy local development, extractable later.
2. **Domain events** (Spring `ApplicationEventPublisher`, outbox table later if needed) decouple booking transitions from notifications, analytics and audit.
3. **State in Redis for the hot path, Postgres for the truth.** Driver location and availability are ephemeral in Redis; durable bookings, rides and payments in Postgres.
4. **Stateless backend** so instances can scale horizontally; WebSocket sessions are node-local, with Redis pub/sub relay.
5. **API-first.** OpenAPI is the contract for four mobile apps and two web apps; generated clients considered in Phase 1.
6. **Config over code.** Business rules read from the configuration service.
7. **Multi-city ready.** `city_id` on drivers, bookings, pricing and configuration from the first migration.
8. **Provider abstraction everywhere** to keep vendors replaceable.

## 4. Security architecture

- **Transport:** TLS everywhere; HSTS; secure headers; strict CORS allow-list per environment.
- **Authentication:** Phone OTP for customers/drivers (hashed OTP, 5-minute expiry, attempt limits, resend throttling, per-phone and per-IP rate limits). Admins: email+password (Argon2id/bcrypt) + TOTP MFA.
- **Tokens:** Short-lived JWT access tokens (e.g. 15 min), rotating refresh tokens stored hashed with device binding and reuse detection; logout and suspension revoke. Mobile stores in Keystore/Keychain. Signing keys supplied via environment/secret manager, rotation supported (`kid`).
- **Authorisation:** Role-based plus resource ownership checks (customer sees own bookings; driver sees assigned rides; admin permission set). Roles come from the token issued by the server and re-checked against the DB for sensitive operations; client-sent roles are ignored. WebSocket subscriptions are authorised per topic.
- **Data protection:** PII encrypted at rest (disk/DB-level plus application-level encryption for document metadata where required); documents in private storage with signed URLs and access logging; field-level masking in logs.
- **Input handling:** Bean Validation on DTOs; output encoding in web apps; parameterised queries via JPA; file upload type/size checks and malware scanning (post-MVP).
- **Abuse prevention:** Rate limiting (Redis token bucket) on auth, booking creation, location endpoints.
- **Audit:** Append-only audit table; document views and location-history access recorded.
- **Payments:** Server-side verification and signed-webhook validation; idempotency keys.
- **Secrets:** Never in the repo; environment variables / secret manager; `.env.example` only.
- **Supply chain:** Dependency scanning and secret scanning in CI.

## 5. Privacy architecture for location data

- Live position only in Redis with TTL (e.g. 30 s without update -> driver treated as offline).
- Historical trail sampled (e.g. every 10–15 s during active ride only; none while idle) and stored in PostGIS tied to a ride.
- Retention job purges trails after a configurable period (default proposal: 90 days; safety incidents retained longer under legal hold).
- Admin access to historical trails requires permission, written reason, and audit entry.
- Customers' position is used only for booking and during active rides.

## 6. Environments

| Env | Purpose | Notes |
|-----|---------|-------|
| development | Local Docker Compose (Postgres+PostGIS, Redis, MinIO, mock providers) | Seed data, fake OTP provider |
| staging | Production-like, sandbox providers | Used for release testing |
| production | Live | Manual promotion; no automatic deploy until strategy configured |

## 7. Cross-cutting concerns

- **Logging:** JSON logs with `requestId`, `userId`, `bookingId`, `rideId`, `driverId`, operation, result via MDC. Sensitive values never logged.
- **Metrics/tracing:** Micrometer + Actuator ready for Prometheus/Grafana; OpenTelemetry-compatible; Sentry hook point.
- **Error handling:** Central `@RestControllerAdvice` with safe error codes.
- **Resilience:** Timeouts, retries with backoff and circuit breakers around providers; graceful fallbacks (e.g. alternate SMS provider).
- **Scheduling:** Search timeouts and offer expiries use Redis-based delayed tasks (or Spring scheduler with persisted state) so they survive restarts.

## 8. Scaling path

1. Single instance + managed Postgres/Redis (pilot).
2. Multiple backend instances behind a load balancer; Redis pub/sub for WebSockets.
3. Read replicas, separate WebSocket/location-ingest service if load requires.
4. Extract matching and real-time modules into services; partition by city.

## 9. Repository structure

```text
lady-go/
├── backend/            Spring Boot (modular monolith)
├── mobile/
│   ├── android-customer/
│   ├── android-driver/
│   ├── ios-customer/
│   └── ios-driver/
├── frontend/
│   ├── website/
│   └── admin/
├── infrastructure/     Docker Compose, IaC, monitoring config
├── docs/
├── .github/workflows/
├── README.md
└── .gitignore
```

## 10. Major API modules

`/api/v1/auth`, `/customers`, `/drivers`, `/bookings`, `/rides`, `/payments`, `/locations`, `/notifications`, `/admin` (see `docs/architecture/backend-architecture.md` for module-level breakdown). WebSocket endpoint: `/ws` with STOMP destinations documented in `realtime-architecture.md`.
