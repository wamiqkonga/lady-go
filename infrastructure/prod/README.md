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

Update: build + `docker load` a new image, then `docker compose up -d backend`.
