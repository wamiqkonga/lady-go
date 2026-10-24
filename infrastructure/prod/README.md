# Production deploy (MVP 1, single host)

Host: one arm64 instance (Docker + compose). Images: `ladygo-backend:local` is built on a dev machine for
`linux/arm64` and shipped with `docker save | ssh ... docker load`; Postgres+PostGIS is built here from
`Dockerfile.postgis`.

Secrets live only in `/opt/ladygo/.env` on the server (chmod 600): `POSTGRES_PASSWORD`, `REDIS_PASSWORD`,
`LADYGO_JWT_SECRET`, `LADYGO_OTP_HASH_SECRET`, `LADYGO_RIDE_PIN_SECRET`, `LADYGO_MFA_ENC_KEY` (exactly 32
chars), plus non-secret config (`LADYGO_CORS_ALLOWED_ORIGINS`, `LADYGO_WEBSOCKET_ALLOWED_ORIGINS`,
`LADYGO_S3_BUCKET`, `LADYGO_S3_REGION`, `LADYGO_OTP_CHANNEL`, `LADYGO_EMAIL_FROM`, `LADYGO_EMAIL_SES_REGION`).
S3 and SES credentials come from the instance role (no access keys on the host).
`LADYGO_GOOGLE_MAPS_API_KEY` is required by the prod profile at startup; driver registration never calls Maps, so a
placeholder is fine until ride booking is enabled, then set the real key.

Run compose with `--env-file /opt/ladygo/.env` (it supplies the interpolated values). Enable HTTPS only once
`api.<domain>` resolves to the host: `docker compose --env-file /opt/ladygo/.env --profile tls up -d caddy`.

## Deploying (automatic)

Merging to `main` runs `.github/workflows/deploy-backend.yml`: tests, then an arm64 image tagged with the git SHA is
pushed to ECR, then `deploy.sh` runs on this host through AWS SSM. `deploy.sh` backs up the database
(`backup-db.sh`), pulls the image, replaces only the backend container, waits for `/actuator/health` and for
`/actuator/info` to show the new commit, and rolls back to the previous image by itself if that does not happen.

- What is live: `curl https://api.ladygoride.com/actuator/info` (`app.commit` is the git SHA).
- Image in use: `/opt/ladygo/stack/image.env`; the one before it: `/opt/ladygo/stack/image.env.prev`.
- Manual rollback: GitHub, Actions, "Deploy backend", Run workflow, enter the full SHA of an older release.
- Last resort on the host: `echo "BACKEND_IMAGE=<old image>" | sudo tee /opt/ladygo/stack/image.env`, then
  `docker compose -f /opt/ladygo/stack/docker-compose.yml --env-file /opt/ladygo/.env --env-file /opt/ladygo/stack/image.env up -d --no-deps backend`.
- Rollback by SHA only reaches the newest 20 images in ECR. Start a manual rollback when no other deploy is queued.
- If the backend log says the Liquibase changelog is locked (a deploy was interrupted mid-migration):
  `docker exec ladygo-postgres-1 psql -U ladygo -d ladygo -c "UPDATE databasechangeloglock SET locked=false"`, then restart the backend.
- A database migration is not undone by a rollback. Keep migrations backward compatible (add, do not drop or
  rename in the same release). To restore data, use the pre-deploy dump in the backups bucket.
- Test the script without a server: `bash infrastructure/prod/deploy.test.sh`.
- One-time AWS and GitHub setup: `docs/development/plans/2026-10-24-main-to-production-deploy.md` (T1-T6).
