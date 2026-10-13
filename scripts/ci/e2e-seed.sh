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

echo "== Raising the admin login rate limit before seeding any accounts =="
# admin.login.max.attempts (default 5 per 15-minute window) is IP-scoped and, by design, never
# released on failure (AdminAuthService.releaseLoginRateLimit's own comment explains why) - MUST
# happen BEFORE the account-seeding loop below, not after: seeding itself calls /login once per
# account (to get an enrolment token), all from this one CI runner's IP, and that IP bucket is never
# released even on a successful enrolment login (only the email-scoped bucket is) - with the default
# limit of 5, account #6 in the loop would already be rate-limited before a single test ever runs.
# This is a test-isolation accommodation, not a weakening of anything under test: the rate limiter
# itself already has dedicated backend coverage (AdminAuthServiceIntegrationTest) that exercises the
# real default.
psql -v ON_ERROR_STOP=1 -q -c \
  "UPDATE configuration SET value = '200' WHERE key = 'admin.login.max.attempts';"

# Creates one staff account with the given role and phone, logs in, enrols MFA, confirms it, and
# prints the TOTP secret on stdout (the only thing the caller needs back - email/password/phone are
# already known to the caller since it chose them). `phone` is an explicit parameter, not a shared
# counter incremented in here - this function is always invoked as `X=$(create_and_enrol_account ...)`,
# a command substitution, which bash runs in a SUBSHELL; any variable this function modified would be
# a copy, discarded the moment the subshell exits, invisible to the next call. A real bug this exact
# mistake caused here once: every account got the SAME phone number, every INSERT after the first
# failed on the unique constraint, and - because the failure was inside a command substitution nested
# in this function - `set -e` at the top of this script did not reliably propagate out and abort the
# run, so the script carried on and ultimately "succeeded" having created exactly one working account.
# Every step below that can fail is followed by an explicit `|| { ...; exit 1; }` for the same reason:
# do not rely on implicit set -e propagation through a command substitution to catch a real failure.
create_and_enrol_account() {
  local email="$1" password="$2" role="$3" phone="$4"

  psql -v ON_ERROR_STOP=1 -q <<SQL || { echo "Failed to insert $email ($phone)" >&2; exit 1; }
INSERT INTO users (email, phone, role, password_hash, must_change_password, mfa_enabled, status)
VALUES ('$email', '$phone', '$role', crypt('$password', gen_salt('bf')), false, false, 'ACTIVE');
SQL

  local login1 enrolment_token secret confirm_code enroll
  login1=$(curl -sf -X POST "$BASE/api/v1/auth/admin/login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$email\",\"password\":\"$password\"}") \
    || { echo "Login failed for $email" >&2; exit 1; }
  enrolment_token=$(echo "$login1" | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['enrolmentToken'])") \
    || { echo "No enrolmentToken in login response for $email: $login1" >&2; exit 1; }
  enroll=$(curl -sf -X POST "$BASE/api/v1/auth/admin/mfa/enroll" -H "Authorization: Bearer $enrolment_token") \
    || { echo "mfa/enroll failed for $email" >&2; exit 1; }
  secret=$(echo "$enroll" | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['secret'])") \
    || { echo "No secret in mfa/enroll response for $email: $enroll" >&2; exit 1; }
  confirm_code=$(totp_code "$secret")
  curl -sf -X POST "$BASE/api/v1/auth/admin/mfa/enroll/confirm" \
    -H "Authorization: Bearer $enrolment_token" -H 'Content-Type: application/json' \
    -d "{\"code\":\"$confirm_code\"}" >/dev/null \
    || { echo "mfa/enroll/confirm failed for $email" >&2; exit 1; }
  if [[ -z "$secret" ]]; then
    echo "Empty TOTP secret for $email - refusing to continue silently." >&2
    exit 1
  fi
  echo "$secret"
}

echo "== Seeding one dedicated, independently-enrolled SUPER_ADMIN account per real test login =="
# Keys match e2e/env.ts's SUPER_ADMIN_KEYS exactly - one per test that performs a real login, so no
# two real logins anywhere in the suite ever share a TOTP secret (see the header comment above).
SUPER_ADMIN_KEYS=(LOGIN QUEUE REVIEW IDLE SIGNOUT WRONGCODE REPLAY DOCEXPIRY TAMPER COOKIES NOSTORAGE MARKUP ROLECHECK)
for i in "${!SUPER_ADMIN_KEYS[@]}"; do
  KEY="${SUPER_ADMIN_KEYS[$i]}"
  EMAIL="e2e.super.$(echo "$KEY" | tr '[:upper:]' '[:lower:]')@ladygo.test"
  PASSWORD=$(openssl rand -base64 18)
  PHONE=$(printf '+9199%08d' "$((i + 1))")
  SECRET=$(create_and_enrol_account "$EMAIL" "$PASSWORD" "SUPER_ADMIN" "$PHONE")
  if [[ -z "$SECRET" ]]; then
    echo "Seeding $KEY produced an empty TOTP secret - aborting rather than emitting a broken credential." >&2
    exit 1
  fi
  mask "$PASSWORD"
  mask "$SECRET"
  emit "ADMIN_WEB_E2E_SUPER_${KEY}_EMAIL" "$EMAIL"
  emit "ADMIN_WEB_E2E_SUPER_${KEY}_PASSWORD" "$PASSWORD"
  emit "ADMIN_WEB_E2E_SUPER_${KEY}_TOTP_SECRET" "$SECRET"
done

echo "== Seeding a dedicated plain-ADMIN account for the ADMIN-vs-SUPER_ADMIN check =="
ADMIN_EMAIL="e2e.admin.rolecheck@ladygo.test"
ADMIN_PASSWORD=$(openssl rand -base64 18)
ADMIN_PHONE=$(printf '+9199%08d' "$((${#SUPER_ADMIN_KEYS[@]} + 1))")
ADMIN_TOTP_SECRET=$(create_and_enrol_account "$ADMIN_EMAIL" "$ADMIN_PASSWORD" "ADMIN" "$ADMIN_PHONE")
if [[ -z "$ADMIN_TOTP_SECRET" ]]; then
  echo "Seeding the ADMIN role-check account produced an empty TOTP secret - aborting." >&2
  exit 1
fi
mask "$ADMIN_PASSWORD"
mask "$ADMIN_TOTP_SECRET"

echo "== Waiting for a fresh TOTP window before any test can log in =="
# Per-account isolation (above) removes collisions BETWEEN accounts, but not within one: each
# account's own MFA enrolment-confirm call just now consumed a TOTP step the same way a login does
# (confirmMfaEnrollment uses the same updateMfaLastUsedStepIfNewer login() does) - if that account's
# one real test login happens to run in the SAME 30s step as its own enrolment moments ago, the
# backend correctly rejects it as a replay. A single bounded wait here, paid once for the whole seed
# step rather than once per login, guarantees every account's enrolment step is strictly in the past
# by the time ANY test's login can run - there is no longer a step for an account to collide with
# itself on either. Worst case ~30s, typically much less; the driver-seeding work below adds further
# buffer on top for free.
python3 -c "
import time
step_seconds = 30
now = time.time()
remaining = step_seconds - (now % step_seconds)
time.sleep(remaining + 1)
"

emit ADMIN_WEB_E2E_ADMIN_EMAIL "$ADMIN_EMAIL"
emit ADMIN_WEB_E2E_ADMIN_PASSWORD "$ADMIN_PASSWORD"
emit ADMIN_WEB_E2E_ADMIN_TOTP_SECRET "$ADMIN_TOTP_SECRET"

echo "== Shortening the document-view URL TTL for the expiry check =="
psql -v ON_ERROR_STOP=1 -q -c \
  "UPDATE configuration SET value = '3' WHERE key = 'verification.document.view_url.ttl.seconds';"

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
