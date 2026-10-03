# Infrastructure

This is the local development stack only. Staging/production deployment, monitoring, backups and the migration strategy are Phase 12.

## Local stack
```bash
cd infrastructure
cp .env.example .env        # change the passwords
docker compose up -d        # PostgreSQL + PostGIS, Redis, MinIO (bound to 127.0.0.1)
docker compose down         # stop (add -v to delete data volumes)
```

| Service | Port | Purpose |
|---------|------|---------|
| PostgreSQL 16 + PostGIS 3.4 | 5432 | Primary database (schema created by the backend's Flyway migrations on startup) |
| Redis 7 | 6379 | Live state, rate limits, pub/sub (Phase 3+) |
| MinIO | 9000 / 9001 | S3-compatible storage for documents (Phase 5) |
| backend (profile `app`) | 8080 | Backend container |

Secrets live only in the git-ignored `.env`. The values in `.env.example` are placeholders for local use.

## Running the backend against this database
- On your machine: `export POSTGRES_PASSWORD=<value from .env>` then `cd backend && mvn spring-boot:run` (dev profile, connects to `localhost:5432`).
- In Docker: `docker compose --profile app up --build` (the compose file passes the datasource settings).
- The `dev` profile also loads a placeholder Srinagar city (`db/dev/R__dev_seed_city.sql`); staging and production never do.
- Tests do not use this database: they start their own PostGIS container with Testcontainers (Docker must be running).
