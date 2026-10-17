# Lady Go

A women-focused pick-and-drop transportation platform: customer and driver mobile apps (Android/iOS), a public website, an admin portal, and a Spring Boot backend.

**Current status: Phase 1 (Project Foundation) and Phase 2 (Database) complete — backend CI is green including the Testcontainers database tests (see "Build status"). Phase 3 (Authentication) is starting.**

## Build status

| Project | Verified on authoring machine | How to verify |
|---------|-------------------------------|---------------|
| `frontend/website` | Lint, type-check, 29 tests (incl. driver self-registration), production build, CSP check pass; Playwright E2E in CI | `npm ci && npm run lint && npm run typecheck && npm test && npm run build` |
| `frontend/admin` | Lint, type-check, 15 tests, production build pass | same |
| `backend` (Phase 1 skeleton) | CI green (build + tests) | `cd backend && mvn verify` (needs Docker for the database tests) |
| `backend` database (Phase 2) | Migrations V1–V11, entities, repositories and invariants verified on PostgreSQL 16 + PostGIS. `DatabaseMigrationTest`, `EnumSchemaParityTest`, `PersistenceMappingTest`, `DatabaseConstraintsTest` green locally and in CI (Testcontainers) | `cd backend && mvn verify` |
| `mobile/android-*` | CI green (lint, unit tests, debug build) | See each app's README |
| `mobile/ios-*` | CI green (build + unit tests on a simulator) | See each app's README |
| `infrastructure` | `docker compose config` validates | `docker compose up -d` |
| `.github/workflows` | All five workflows have run | Push a branch |

Phase 2 closed 2026-10-04: backend CI green with the database tests.

## Getting started (development)

```bash
# 1. Infrastructure (PostgreSQL+PostGIS, Redis, MinIO)
cd infrastructure && cp .env.example .env && docker compose up -d

# 2. Backend (http://localhost:8080, health: /actuator/health, ping: /api/v1/system/ping)
#    Applies the Flyway migrations on startup. The password is the POSTGRES_PASSWORD from infrastructure/.env.
cd backend && export POSTGRES_PASSWORD='<same value as in infrastructure/.env>' && mvn spring-boot:run

# 3. Website (http://localhost:5173) and admin portal (http://localhost:5174)
cd frontend/website && npm ci && npm run dev
cd frontend/admin   && npm ci && npm run dev
```

Android and iOS: see `mobile/*/README.md`.

## Repository structure

```text
lady-go/
├── backend/               Java 21 + Spring Boot 3 (modular monolith)
├── mobile/
│   ├── android-customer/  Kotlin, Jetpack Compose
│   ├── android-driver/
│   ├── ios-customer/      Swift, SwiftUI
│   └── ios-driver/
├── frontend/
│   ├── website/           React + TypeScript (public site)
│   └── admin/             React + TypeScript (operations portal)
├── infrastructure/        Docker Compose, deployment, monitoring
├── docs/
│   ├── architecture/
│   ├── api/
│   ├── database/
│   ├── deployment/
│   ├── development/
│   ├── product/
│   └── security/
├── .github/workflows/     CI
├── README.md
└── .gitignore
```

## Technology

Backend: Java 21, Spring Boot 3, Spring Security, Spring Data JPA, PostgreSQL + PostGIS, Redis, WebSocket/STOMP, Flyway, OpenAPI.
Mobile: Kotlin + Jetpack Compose + Hilt + Retrofit; Swift + SwiftUI + MapKit.
Web: React + TypeScript.

## Documentation index

| Document | Purpose |
|----------|---------|
| [Requirements](docs/product/requirements.md) | Scope, MVP vs post-MVP, risks, decisions |
| [User flows](docs/product/user-flows.md) | Customer and driver journeys, booking state machine |
| [Admin flows](docs/product/admin-flows.md) | Roles, live map, verification, safety handling |
| [System architecture](docs/architecture/system-architecture.md) | Components, security, privacy, environments |
| [Backend architecture](docs/architecture/backend-architecture.md) | Modules, API conventions, matching, pricing |
| [Real-time architecture](docs/architecture/realtime-architecture.md) | WebSocket, Redis, tracking |
| [Database design](docs/database/database-design.md) | Entities, indexes, migrations plan |
| [Roadmap](docs/development/roadmap.md) | Phases, open decisions |

Planned later: `docs/database/erd.md`, `docs/api/api-overview.md`, `docs/security/security.md`, `docs/deployment/deployment.md`.

## Principles

Safety first, privacy by design, configuration over hard-coded rules, replaceable providers, test everything on critical paths, no secrets in source control.

## Next step

Phase 3 — Authentication (OTP, JWT, refresh tokens, RBAC, rate limiting, admin MFA). Design in `docs/security/auth-design.md`.
