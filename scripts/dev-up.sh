#!/usr/bin/env bash
# Starts a full local stack for manually exercising the admin web: throwaway Postgres (Docker),
# the backend (dev profile only - refuses anything else), the admin web dev server, a bootstrapped
# SUPER_ADMIN with MFA already enrolled, and one seeded driver with a vehicle and pending documents.
#
# Every secret this script generates is printed to THIS TERMINAL ONLY and never written to a file -
# not even a gitignored one - matching docs/development/auth-local-runbook.md's own rule. State
# (PIDs, container names, ports) needed by dev-down.sh lives in .dev-stack/, which is gitignored.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_DIR="$ROOT/.dev-stack"
mkdir -p "$STATE_DIR"

if [[ -f "$STATE_DIR/state.env" ]]; then
  echo "A dev stack already looks like it's running ($STATE_DIR/state.env exists)." >&2
  echo "Run scripts/dev-down.sh first, then retry." >&2
  exit 1
fi

if [[ -n "${SPRING_PROFILES_ACTIVE:-}" && "${SPRING_PROFILES_ACTIVE}" != "dev" ]]; then
  echo "SPRING_PROFILES_ACTIVE is set to '${SPRING_PROFILES_ACTIVE}', not 'dev'. Refusing to run -" >&2
  echo "this script bootstraps throwaway credentials and seed data that must never touch a real environment." >&2
  exit 1
fi

for cmd in docker mvn npm python3 openssl curl; do
  command -v "$cmd" >/dev/null || { echo "Missing required command: $cmd" >&2; exit 1; }
done

echo "== Generating throwaway secrets (this session only, never written to disk) =="
POSTGRES_PASSWORD=$(openssl rand -base64 24)
LADYGO_JWT_SECRET=$(openssl rand -base64 48)
LADYGO_OTP_HASH_SECRET=$(openssl rand -base64 48)
LADYGO_MFA_ENC_KEY=$(openssl rand -base64 24 | cut -c1-32)
LADYGO_RIDE_PIN_SECRET=$(openssl rand -base64 48)
LADYGO_LOCAL_STORAGE_SECRET=$(openssl rand -base64 48)
BOOTSTRAP_EMAIL="super.admin@ladygo.local"
BOOTSTRAP_PASSWORD=$(openssl rand -base64 18)
BOOTSTRAP_PHONE="+919900011122"

PG_PORT=55432
PG_CONTAINER="ladygo-devup-postgres"

echo "== Starting throwaway Postgres (container: $PG_CONTAINER, port: $PG_PORT) =="
docker rm -f "$PG_CONTAINER" >/dev/null 2>&1 || true
docker run -d --name "$PG_CONTAINER" \
  -e POSTGRES_DB=ladygo -e POSTGRES_USER=ladygo -e POSTGRES_PASSWORD="$POSTGRES_PASSWORD" \
  -p "127.0.0.1:${PG_PORT}:5432" postgis/postgis:16-3.4 >/dev/null

until docker exec "$PG_CONTAINER" pg_isready -U ladygo -d ladygo >/dev/null 2>&1; do sleep 1; done
echo "Postgres ready."

echo "== Starting backend (dev profile, port 8080) =="
BACKEND_LOG="$STATE_DIR/backend.log"
(
  cd "$ROOT/backend"
  export SPRING_PROFILES_ACTIVE=dev
  export LADYGO_ADMIN_MFA_REQUIRED=true # the enrolment flow below needs MFA on; the base default is off
  export SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:${PG_PORT}/ladygo"
  export SPRING_DATASOURCE_USERNAME=ladygo
  export SPRING_DATASOURCE_PASSWORD="$POSTGRES_PASSWORD"
  export LADYGO_CORS_ALLOWED_ORIGINS="http://localhost:5173,http://localhost:5174"
  export LADYGO_JWT_SECRET LADYGO_OTP_HASH_SECRET LADYGO_MFA_ENC_KEY LADYGO_RIDE_PIN_SECRET LADYGO_LOCAL_STORAGE_SECRET
  export LADYGO_BOOTSTRAP_ADMIN_EMAIL="$BOOTSTRAP_EMAIL"
  export LADYGO_BOOTSTRAP_ADMIN_PASSWORD="$BOOTSTRAP_PASSWORD"
  export LADYGO_BOOTSTRAP_ADMIN_PHONE="$BOOTSTRAP_PHONE"
  nohup mvn -q spring-boot:run >"$BACKEND_LOG" 2>&1 &
  echo $! > "$STATE_DIR/backend.pid"
)
BACKEND_PID=$(cat "$STATE_DIR/backend.pid")

echo "Waiting for backend startup (this can take ~30-60s)..."
for _ in $(seq 1 120); do
  grep -q "Started LadyGoApplication" "$BACKEND_LOG" 2>/dev/null && break
  if grep -q "APPLICATION FAILED TO START" "$BACKEND_LOG" 2>/dev/null; then
    echo "Backend failed to start - see $BACKEND_LOG" >&2
    exit 1
  fi
  sleep 1
done
grep -q "Started LadyGoApplication" "$BACKEND_LOG" 2>/dev/null || { echo "Backend did not start within 120s - see $BACKEND_LOG" >&2; exit 1; }
echo "Backend up."

BASE=http://localhost:8080

echo "== Bootstrapping SUPER_ADMIN and enrolling MFA =="
LOGIN1=$(curl -sf -X POST "$BASE/api/v1/auth/admin/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$BOOTSTRAP_EMAIL\",\"password\":\"$BOOTSTRAP_PASSWORD\"}")
ENROLMENT_TOKEN=$(echo "$LOGIN1" | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['enrolmentToken'])")

NEW_PASSWORD=$(openssl rand -base64 18)
curl -sf -X POST "$BASE/api/v1/auth/admin/password/change" \
  -H "Authorization: Bearer $ENROLMENT_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"currentPassword\":\"$BOOTSTRAP_PASSWORD\",\"newPassword\":\"$NEW_PASSWORD\"}" >/dev/null

LOGIN2=$(curl -sf -X POST "$BASE/api/v1/auth/admin/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$BOOTSTRAP_EMAIL\",\"password\":\"$NEW_PASSWORD\"}")
ENROLMENT_TOKEN=$(echo "$LOGIN2" | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['enrolmentToken'])")

ENROLL=$(curl -sf -X POST "$BASE/api/v1/auth/admin/mfa/enroll" -H "Authorization: Bearer $ENROLMENT_TOKEN")
TOTP_SECRET=$(echo "$ENROLL" | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['secret'])")

totp_code() {
  python3 - "$TOTP_SECRET" "${1:-0}" <<'PYEOF'
import base64, hmac, hashlib, struct, time, sys
secret, offset = sys.argv[1], int(sys.argv[2])
key = base64.b32decode(secret)
step = int(time.time() // 30) + offset
h = hmac.new(key, struct.pack(">Q", step), hashlib.sha1).digest()
o = h[-1] & 0x0F
code = (struct.unpack(">I", h[o:o+4])[0] & 0x7FFFFFFF) % 1_000_000
print(f"{code:06d}")
PYEOF
}

CODE=$(totp_code 0)
curl -sf -X POST "$BASE/api/v1/auth/admin/mfa/enroll/confirm" \
  -H "Authorization: Bearer $ENROLMENT_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"code\":\"$CODE\"}" >/dev/null
echo "MFA enrolled."

echo "== Seeding one city, driver, vehicle and pending documents =="
docker exec "$PG_CONTAINER" psql -U ladygo -d ladygo -v ON_ERROR_STOP=1 -c "
INSERT INTO cities (name, country, timezone, currency, service_area)
VALUES ('Srinagar', 'IN', 'Asia/Kolkata', 'INR',
  ST_GeogFromText('SRID=4326;POLYGON((74 34,75 34,75 34.5,74 34.5,74 34))'))
ON CONFLICT (name, country) DO NOTHING;
" >/dev/null
CITY_ID=$(docker exec "$PG_CONTAINER" psql -U ladygo -d ladygo -t -A -c \
  "SELECT id FROM cities WHERE name='Srinagar' AND country='IN';")

DRIVER_PHONE="+919900022233"
curl -sf -X POST "$BASE/api/v1/auth/otp/request" -H 'Content-Type: application/json' \
  -d "{\"phone\":\"$DRIVER_PHONE\",\"purpose\":\"LOGIN\",\"intendedRole\":\"DRIVER\"}" >/dev/null
sleep 1
OTP_CODE=$(grep "phone=$DRIVER_PHONE" "$BACKEND_LOG" | tail -1 | grep -oE 'code=[0-9]+' | cut -d= -f2)
DRIVER_LOGIN=$(curl -sf -X POST "$BASE/api/v1/auth/otp/verify" -H 'Content-Type: application/json' \
  -d "{\"phone\":\"$DRIVER_PHONE\",\"code\":\"$OTP_CODE\",\"purpose\":\"LOGIN\",\"intendedRole\":\"DRIVER\",\"deviceId\":\"devup\"}")
DRIVER_TOKEN=$(echo "$DRIVER_LOGIN" | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['accessToken'])")

curl -sf -X POST "$BASE/api/v1/drivers/me" -H "Authorization: Bearer $DRIVER_TOKEN" \
  -H 'Content-Type: application/json' -d "{\"cityId\":\"$CITY_ID\"}" >/dev/null

curl -sf -X PUT "$BASE/api/v1/drivers/me/vehicle" -H "Authorization: Bearer $DRIVER_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"registrationNumber":"JK01AB1234","vehicleType":"CAB","make":"Maruti","model":"Dzire","color":"White","manufactureYear":2022}' >/dev/null

# "v1" is the only seeded declaration_versions row (V16 migration) - not fetched dynamically since
# there's no GET-current-version endpoint; if that migration's seed ever changes, update this too.
curl -sf -X POST "$BASE/api/v1/drivers/me/declaration" -H "Authorization: Bearer $DRIVER_TOKEN" \
  -H 'Content-Type: application/json' -d '{"declarationVersion":"v1"}' >/dev/null

SEED_IMAGE="$STATE_DIR/seed-document.png"
python3 - "$SEED_IMAGE" <<'PYEOF'
import struct, zlib, sys

def chunk(tag, data):
    return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data))

width, height = 20, 20
raw = b""
for _ in range(height):
    raw += b"\x00" + bytes([200, 100, 200] * width)
png = b"\x89PNG\r\n\x1a\n"
png += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
png += chunk(b"IDAT", zlib.compress(raw))
png += chunk(b"IEND", b"")
with open(sys.argv[1], "wb") as f:
    f.write(png)
PYEOF

for TYPE in LICENSE VEHICLE_REGISTRATION VEHICLE_PHOTO INSURANCE; do
  curl -sf -X POST "$BASE/api/v1/drivers/me/documents?type=$TYPE" \
    -H "Authorization: Bearer $DRIVER_TOKEN" \
    -F "file=@${SEED_IMAGE};type=image/png" >/dev/null
done
curl -sf -X POST "$BASE/api/v1/drivers/me/selfie" \
  -H "Authorization: Bearer $DRIVER_TOKEN" \
  -F "file=@${SEED_IMAGE};type=image/png" >/dev/null

echo "Seeded driver $DRIVER_PHONE with vehicle JK01AB1234 and 5 pending documents."

echo "== Starting admin web dev server (port 5174) =="
ADMIN_LOG="$STATE_DIR/admin-web.log"
(
  cd "$ROOT/frontend/admin"
  VITE_ADMIN_MFA_REQUIRED=true nohup npm run dev >"$ADMIN_LOG" 2>&1 &
  echo $! > "$STATE_DIR/admin-web.pid"
)

cat > "$STATE_DIR/state.env" <<EOF
PG_CONTAINER=$PG_CONTAINER
BACKEND_PID=$BACKEND_PID
EOF

cat <<EOF

========================================================================
Dev stack is up.

  Backend:    http://localhost:8080   (log: $BACKEND_LOG)
  Admin web:  http://localhost:5174   (log: $ADMIN_LOG)

  SUPER_ADMIN login:
    Email:        $BOOTSTRAP_EMAIL
    Password:     $NEW_PASSWORD
    TOTP secret:  $TOTP_SECRET
    (compute a fresh code: it changes every 30s. Use a real authenticator
     app with the secret above, or docs/development/auth-local-runbook.md's
     python3 snippet.)

  Seeded driver (for the review queue): $DRIVER_PHONE, vehicle JK01AB1234,
  5 documents pending review.

  When done: scripts/dev-down.sh
========================================================================
EOF
