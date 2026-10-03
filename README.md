# Lady Go

A women-focused pick-and-drop transportation platform: customer and driver mobile apps (Android/iOS), a public website, an admin portal, and a Spring Boot backend.

**Current status: Phase 1 (Project Foundation) written. Website and admin portal build and pass tests. Backend, Android and iOS skeletons are written but not yet compiled (see "Build status").**

## Build status (Phase 1)

| Project | Verified on authoring machine | How to verify |
|---------|-------------------------------|---------------|
| `frontend/website` | Lint, type-check, 13 tests, production build pass | `npm ci && npm run lint && npm run typecheck && npm test && npm run build` |
| `frontend/admin` | Lint, type-check, 15 tests, production build pass | same |
| `backend` | **Not compiled** (Maven Central blocked on the authoring machine); syntax-checked only | `cd backend && mvn verify` |
| `mobile/android-*` | **Not built** (no Android SDK, Google Maven blocked) | See each app's README |
| `mobile/ios-*` | **Not built** (Linux machine, no Xcode) | See each app's README |
| `infrastructure` | `docker compose config` validates | `docker compose up -d` |
| `.github/workflows` | YAML validated only; never run | Push a branch |

Until CI has run green for backend, Android and iOS, treat those three as unverified.

## Getting started (development)

```bash
# 1. Infrastructure (PostgreSQL+PostGIS, Redis, MinIO)
cd infrastructure && cp .env.example .env && docker compose up -d

# 2. Backend (http://localhost:8080, health: /actuator/health, ping: /api/v1/system/ping)
cd backend && mvn spring-boot:run

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

Phase 2 — Database (Flyway migrations, PostGIS, entities). First make backend, Android and iOS CI green.
