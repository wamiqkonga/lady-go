#!/usr/bin/env bash
# Seeds everything frontend/website's Playwright suite (frontend/website/e2e/)
# needs, against a real backend + Postgres already running in CI (.github/workflows/
# driver-registration-e2e.yml starts both before calling this).
#
# Much lighter than scripts/ci/e2e-seed.sh (the admin-web seed step): the customer/driver OTP login
# flow auto-creates a User row on first verify (OtpLoginService.verifyOtp), and this app's own UI
# flow creates the Driver row (bootstrapProfile) - there is no staff MFA enrolment step here at all,
# so almost every test phone number needs NO backend pre-seeding beyond being unique. The one
# exception is the reject->resubmit test, which needs a document already REJECTED before the test
# starts - that one step is seeded here via direct SQL, same pattern as this session's earlier
# ride/flag oversight fixture (scripts/ci/e2e-seed.sh's own "completed cash ride with one open flag"
# block) - fixture setup, not something worth driving through a real admin review UI.
set -euo pipefail

BASE="${DRIVER_REG_E2E_API_BASE_URL:?set by the workflow}"
BACKEND_LOG="${BACKEND_LOG:?set by the workflow - LoggingSmsSender logs the OTP code there}"
: "${PGHOST:=localhost}"
: "${PGPORT:=5432}"
: "${PGUSER:?set by the workflow}"
: "${PGPASSWORD:?set by the workflow}"
: "${PGDATABASE:?set by the workflow}"
export PGHOST PGPORT PGUSER PGPASSWORD PGDATABASE

emit() { echo "$1=$2" >> "${GITHUB_ENV:?}"; }

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
    raw += b"\x00" + bytes([180, 120, 180] * width)
png = b"\x89PNG\r\n\x1a\n"
png += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
png += chunk(b"IDAT", zlib.compress(raw))
png += chunk(b"IEND", b"")
with open(sys.argv[1], "wb") as f:
    f.write(png)
PYEOF

echo "== Phone numbers for the 4 tests that need no backend pre-seeding =="
emit DRIVER_REG_E2E_PHONE_HAPPY_PATH "+919911000001"
emit DRIVER_REG_E2E_PHONE_WRONG_FILE_TYPE "+919911000002"
emit DRIVER_REG_E2E_PHONE_OVERSIZED_FILE "+919911000003"
emit DRIVER_REG_E2E_PHONE_EXPIRED_INSURANCE "+919911000004"

echo "== Seeding a driver with one already-REJECTED licence document (reject-then-resubmit test) =="
REJECT_PHONE="+919911000005"
curl -sf -X POST "$BASE/api/v1/auth/otp/request" -H 'Content-Type: application/json' \
  -d "{\"phone\":\"$REJECT_PHONE\",\"purpose\":\"LOGIN\",\"intendedRole\":\"DRIVER\"}" >/dev/null
sleep 1
otp_code=$(grep "phone=$REJECT_PHONE " "$BACKEND_LOG" | tail -1 | grep -oE 'code=[0-9]+' | cut -d= -f2)
if [[ -z "$otp_code" ]]; then
  echo "Could not find the logged OTP for $REJECT_PHONE in $BACKEND_LOG." >&2
  exit 1
fi
login=$(curl -sf -X POST "$BASE/api/v1/auth/otp/verify" -H 'Content-Type: application/json' \
  -d "{\"phone\":\"$REJECT_PHONE\",\"code\":\"$otp_code\",\"purpose\":\"LOGIN\",\"intendedRole\":\"DRIVER\",\"deviceId\":\"e2e-seed\"}")
token=$(echo "$login" | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['accessToken'])")

curl -sf -X POST "$BASE/api/v1/drivers/me" -H "Authorization: Bearer $token" \
  -H 'Content-Type: application/json' -d "{\"cityId\":\"$CITY_ID\"}" >/dev/null
curl -sf -X POST "$BASE/api/v1/drivers/me/documents?type=LICENSE" \
  -H "Authorization: Bearer $token" -F "file=@${SEED_IMAGE};type=image/png" >/dev/null

psql -v ON_ERROR_STOP=1 -q -c "
UPDATE driver_documents SET status = 'REJECTED', review_note = 'Photo is blurry, please retake.',
  reviewed_at = now()
WHERE driver_id = (SELECT id FROM drivers WHERE user_id = (SELECT id FROM users WHERE phone = '$REJECT_PHONE'))
  AND type = 'LICENSE';
"
emit DRIVER_REG_E2E_PHONE_REJECT_RESUBMIT "$REJECT_PHONE"

echo "== Waiting out the OTP resend cooldown before any test can log in as $REJECT_PHONE =="
# otp.resend.cooldown.seconds (default 30s) applies per phone+purpose - the test itself will request
# its own fresh OTP for this exact phone to log in and see the rejected document, and the seed step
# above just requested one for login. An explicit, bounded wait, not an assumption that CI's later
# steps (npm ci, Playwright browser install, starting the dev server) are slow enough to cover it -
# confirmed by actually hitting "Too many requests" running this against a real backend with no gap
# in between (same lesson as scripts/ci/e2e-seed.sh's own explicit TOTP-window wait, learned the same
# way: by running it, not by estimating).
sleep 31

emit DRIVER_REG_E2E_API_BASE_URL "$BASE"
emit DRIVER_REG_E2E_BACKEND_LOG "$BACKEND_LOG"

echo "Seed complete. City $CITY_ID."
