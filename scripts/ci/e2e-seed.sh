#!/usr/bin/env bash
# Seeds everything frontend/admin's Playwright suite (frontend/admin/e2e/) needs, against a real
# backend + Postgres already running in CI (.github/workflows/admin-web-e2e.yml starts both before
# calling this). Mirrors scripts/dev-up.sh's seeding logic but:
#   - runs against a Postgres the workflow's `services:` block started (no Docker container of its
#     own to manage), reached via the standard PG* env vars `psql` already understands.
#   - creates every staff account (SUPER_ADMIN and ADMIN alike) directly via SQL + pgcrypto's bcrypt,
#     not through AdminBootstrapRunner (which only ever creates one, the very first SUPER_ADMIN) or
#     any admin-creates-admin API endpoint (there isn't one) - uniform for all of them, see "ONE
#     TOTP-ENROLLED ACCOUNT PER REAL LOGIN" below for why there are this many.
#   - seeds TWO drivers, not one: the primary driver is fully mutated (approved) by the happy-path
#     spec, so the negative-checks spec gets its own secondary driver, whose documents it never
#     finishes approving - this is a test-isolation concern, not a security one.
#   - writes results as `NAME=value` lines to $GITHUB_ENV (masking secrets with ::add-mask:: first)
#     rather than printing to a human's terminal - this runs on an ephemeral CI runner destroyed at
#     job end, generating fresh random throwaway credentials every run, which is a different
#     situation from scripts/dev-up.sh's rule (never write a developer's own long-lived secrets to a
#     file) that this script does not violate.
#
# ONE TOTP-ENROLLED ACCOUNT PER REAL LOGIN (root-cause fix, not a workaround):
# the suite used to share one SUPER_ADMIN account across every test and paper over TOTP's 30-second
# replay window with a flat minimum-gap wait between logins. That still missed a real collision: MFA
# *enrolment* (confirmMfaEnrollment) accepts a code and records its step via the same
# updateMfaLastUsedStepIfNewer the login path uses, so the seed step's own enrolment-confirm call for
# an account could land in the same 30s step as that account's first test login moments later -
# correctly rejected by the backend as a replay, since from the server's point of view it is one.
# Giving every real login its own dedicated, independently-enrolled account removes the shared TOTP
# state entirely - there is no step for two logins to collide on, because no two real logins ever use
# the same secret. The one deliberate exception is the replay test itself, which WANTS its own two
# logins to collide (that's what it's testing) and gets its own account so nothing else interferes
# with it.
set -euo pipefail

BASE="${ADMIN_WEB_E2E_API_BASE_URL:?set by the workflow}"
BACKEND_LOG="${BACKEND_LOG:?set by the workflow - MockSmsService logs the OTP code there, since codes are hashed at rest, same as scripts/dev-up.sh}"
: "${PGHOST:=localhost}"
: "${PGPORT:=5432}"
: "${PGUSER:?set by the workflow}"
: "${PGPASSWORD:?set by the workflow}"
: "${PGDATABASE:?set by the workflow}"
export PGHOST PGPORT PGUSER PGPASSWORD PGDATABASE

mask() { echo "::add-mask::$1"; }
emit() { echo "$1=$2" >> "${GITHUB_ENV:?}"; }

totp_code() {
  python3 - "$1" <<'PYEOF'
import base64, hmac, hashlib, struct, time, sys
secret = sys.argv[1]
key = base64.b32decode(secret)
step = int(time.time() // 30)
h = hmac.new(key, struct.pack(">Q", step), hashlib.sha1).digest()
o = h[-1] & 0x0F
code = (struct.unpack(">I", h[o:o+4])[0] & 0x7FFFFFFF) % 1_000_000
print(f"{code:06d}")
PYEOF
}

psql -v ON_ERROR_STOP=1 -q -c "CREATE EXTENSION IF NOT EXISTS pgcrypto;"

PHONE_COUNTER=0

# Creates one staff account with the given role, logs in, enrols MFA, confirms it, and prints the
# TOTP secret on stdout (the only thing the caller needs back - email/password are already known to
# the caller since it chose them).
create_and_enrol_account() {
  local email="$1" password="$2" role="$3"
  PHONE_COUNTER=$((PHONE_COUNTER + 1))
  local phone
  phone=$(printf '+9199%08d' "$PHONE_COUNTER")

  psql -v ON_ERROR_STOP=1 -q <<SQL
INSERT INTO users (email, phone, role, password_hash, must_change_password, mfa_enabled, status)
VALUES ('$email', '$phone', '$role', crypt('$password', gen_salt('bf')), false, false, 'ACTIVE');
SQL

  local login1 enrolment_token secret confirm_code enroll
  login1=$(curl -sf -X POST "$BASE/api/v1/auth/admin/login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$email\",\"password\":\"$password\"}")
  enrolment_token=$(echo "$login1" | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['enrolmentToken'])")
  enroll=$(curl -sf -X POST "$BASE/api/v1/auth/admin/mfa/enroll" -H "Authorization: Bearer $enrolment_token")
  secret=$(echo "$enroll" | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['secret'])")
  confirm_code=$(totp_code "$secret")
  curl -sf -X POST "$BASE/api/v1/auth/admin/mfa/enroll/confirm" \
    -H "Authorization: Bearer $enrolment_token" -H 'Content-Type: application/json' \
    -d "{\"code\":\"$confirm_code\"}" >/dev/null
  echo "$secret"
}

echo "== Seeding one dedicated, independently-enrolled SUPER_ADMIN account per real test login =="
# Keys match e2e/env.ts's SUPER_ADMIN_KEYS exactly - one per test that performs a real login, so no
# two real logins anywhere in the suite ever share a TOTP secret (see the header comment above).
SUPER_ADMIN_KEYS=(LOGIN QUEUE REVIEW IDLE SIGNOUT WRONGCODE REPLAY DOCEXPIRY TAMPER COOKIES NOSTORAGE MARKUP ROLECHECK)
for KEY in "${SUPER_ADMIN_KEYS[@]}"; do
  EMAIL="e2e.super.$(echo "$KEY" | tr '[:upper:]' '[:lower:]')@ladygo.test"
  PASSWORD=$(openssl rand -base64 18)
  SECRET=$(create_and_enrol_account "$EMAIL" "$PASSWORD" "SUPER_ADMIN")
  mask "$PASSWORD"
  mask "$SECRET"
  emit "ADMIN_WEB_E2E_SUPER_${KEY}_EMAIL" "$EMAIL"
  emit "ADMIN_WEB_E2E_SUPER_${KEY}_PASSWORD" "$PASSWORD"
  emit "ADMIN_WEB_E2E_SUPER_${KEY}_TOTP_SECRET" "$SECRET"
done

echo "== Seeding a dedicated plain-ADMIN account for the ADMIN-vs-SUPER_ADMIN check =="
ADMIN_EMAIL="e2e.admin.rolecheck@ladygo.test"
ADMIN_PASSWORD=$(openssl rand -base64 18)
ADMIN_TOTP_SECRET=$(create_and_enrol_account "$ADMIN_EMAIL" "$ADMIN_PASSWORD" "ADMIN")
mask "$ADMIN_PASSWORD"
mask "$ADMIN_TOTP_SECRET"
emit ADMIN_WEB_E2E_ADMIN_EMAIL "$ADMIN_EMAIL"
emit ADMIN_WEB_E2E_ADMIN_PASSWORD "$ADMIN_PASSWORD"
emit ADMIN_WEB_E2E_ADMIN_TOTP_SECRET "$ADMIN_TOTP_SECRET"

echo "== Shortening the document-view URL TTL for the expiry check =="
psql -v ON_ERROR_STOP=1 -q -c \
  "UPDATE configuration SET value = '3' WHERE key = 'verification.document.view_url.ttl.seconds';"

echo "== Raising the admin login rate limit for this suite =="
# admin.login.max.attempts (default 5 per 15-minute window) is IP-scoped and, by design, never
# released on failure (AdminAuthService.releaseLoginRateLimit's own comment explains why) - every
# Playwright test in this suite originates from the same real IP, and 14 accounts' worth of
# enrolment logins plus every test's own login easily exceeds 5 attempts from that one IP. This is a
# test-isolation accommodation, not a weakening of anything under test: the rate limiter itself
# already has dedicated backend coverage (AdminAuthServiceIntegrationTest) that exercises the real
# default.
psql -v ON_ERROR_STOP=1 -q -c \
  "UPDATE configuration SET value = '200' WHERE key = 'admin.login.max.attempts';"

echo "== Seeding one city =="
psql -v ON_ERROR_STOP=1 -q -c "
INSERT INTO cities (name, country, timezone, currency, service_area)
VALUES ('Srinagar', 'IN', 'Asia/Kolkata', 'INR',
  ST_GeogFromText('SRID=4326;POLYGON((74 34,75 34,75 34.5,74 34.5,74 34))'))
ON CONFLICT (name, country) DO NOTHING;
"
CITY_ID=$(psql -t -A -c "SELECT id FROM cities WHERE name='Srinagar' AND country='IN';")

SEED_IMAGE="$(mktemp -d)/seed-document.png"
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

seed_driver() {
  local phone="$1" registration="$2"
  curl -sf -X POST "$BASE/api/v1/auth/otp/request" -H 'Content-Type: application/json' \
    -d "{\"phone\":\"$phone\",\"purpose\":\"LOGIN\",\"intendedRole\":\"DRIVER\"}" >/dev/null
  sleep 1
  local otp_code driver_login driver_token
  otp_code=$(grep "phone=$phone" "$BACKEND_LOG" | tail -1 | grep -oE 'code=[0-9]+' | cut -d= -f2)
  if [[ -z "$otp_code" ]]; then
    echo "Could not find the MockSmsService-logged OTP for $phone in $BACKEND_LOG." >&2
    exit 1
  fi
  driver_login=$(curl -sf -X POST "$BASE/api/v1/auth/otp/verify" -H 'Content-Type: application/json' \
    -d "{\"phone\":\"$phone\",\"code\":\"$otp_code\",\"purpose\":\"LOGIN\",\"intendedRole\":\"DRIVER\",\"deviceId\":\"e2e\"}")
  driver_token=$(echo "$driver_login" | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['accessToken'])")

  curl -sf -X POST "$BASE/api/v1/drivers/me" -H "Authorization: Bearer $driver_token" \
    -H 'Content-Type: application/json' -d "{\"cityId\":\"$CITY_ID\"}" >/dev/null
  curl -sf -X PUT "$BASE/api/v1/drivers/me/vehicle" -H "Authorization: Bearer $driver_token" \
    -H 'Content-Type: application/json' \
    -d "{\"registrationNumber\":\"$registration\",\"vehicleType\":\"CAB\",\"make\":\"Maruti\",\"model\":\"Dzire\",\"color\":\"White\",\"manufactureYear\":2022}" >/dev/null
  curl -sf -X POST "$BASE/api/v1/drivers/me/declaration" -H "Authorization: Bearer $driver_token" \
    -H 'Content-Type: application/json' -d '{"declarationVersion":"v1"}' >/dev/null
  for TYPE in LICENSE VEHICLE_REGISTRATION VEHICLE_PHOTO INSURANCE; do
    curl -sf -X POST "$BASE/api/v1/drivers/me/documents?type=$TYPE" \
      -H "Authorization: Bearer $driver_token" -F "file=@${SEED_IMAGE};type=image/png" >/dev/null
  done
  curl -sf -X POST "$BASE/api/v1/drivers/me/selfie" \
    -H "Authorization: Bearer $driver_token" -F "file=@${SEED_IMAGE};type=image/png" >/dev/null

  psql -t -A -c "SELECT id FROM drivers WHERE user_id = (SELECT id FROM users WHERE phone = '$phone');"
}

echo "== Seeding the primary driver (mutated to APPROVED by the happy-path spec) =="
DRIVER_ID=$(seed_driver "+919900077001" "JK01AB1234")
echo "== Seeding the secondary driver (left PENDING for the negative-checks spec) =="
SECONDARY_DRIVER_ID=$(seed_driver "+919900077002" "JK01CD5678")

emit ADMIN_WEB_E2E_API_BASE_URL "$BASE"
emit ADMIN_WEB_E2E_DRIVER_ID "$DRIVER_ID"
emit ADMIN_WEB_E2E_SECONDARY_DRIVER_ID "$SECONDARY_DRIVER_ID"

echo "Seed complete. Driver $DRIVER_ID (primary), $SECONDARY_DRIVER_ID (secondary)."
