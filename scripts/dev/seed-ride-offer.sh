#!/usr/bin/env bash
# Dev-only: creates a real rider booking against a local backend, close enough to the driver
# scripts/dev-up.sh already seeds (phone +919900022233) that MatchingService should offer it to that
# driver - so the driver Android app's "go online" + incoming-offer flow has something real to
# receive over STOMP when manually tested in the emulator, instead of requiring a second physical
# phone/rider app to generate one.
#
# Does NOT create the driver, their vehicle, or approve any of it - run, in order:
#   1. scripts/dev-up.sh                       (seeds the driver + vehicle + pending documents)
#   2. scripts/dev/approve-seeded-driver.sh    (approves them, so "go online" can succeed)
#   3. Open the driver Android app, log in as the phone number below, and tap "Go online" -
#      BEFORE running this script, not after. The server claims ONLINE -> BUSY the moment the
#      booking below is matched, and only a driver who was already ONLINE can be matched at all -
#      a driver still OFFLINE at that point is simply never considered, and "Go online" tapped
#      afterwards would hit the server's real "cannot change availability directly while BUSY"
#      rejection (confirmed against DriverAvailabilityService) with no way to recover except
#      waiting for the ride to resolve.
#   4. this script                              (creates a rider + booking near the driver)
#
# No direct database access anywhere in this script, same "go through the real APIs" rule as
# approve-seeded-driver.sh - the one exception is reading the OTP code back out of the backend's log
# (scripts/dev-up.sh's own BACKEND_LOG), the same trick every seed script in this repo already uses,
# since OTP codes are hashed at rest and there is no other way to read one back.
#
# Usage: BACKEND_LOG=<path dev-up.sh printed> scripts/dev/seed-ride-offer.sh
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
case "$BASE_URL" in
  http://localhost:*|http://127.0.0.1:*) ;;
  *)
    echo "BASE_URL ('$BASE_URL') does not look like a local backend. Refusing to run - this script" >&2
    echo "creates a real booking (and sets a real driver's location) and must only ever target a" >&2
    echo "throwaway local dev stack (scripts/dev-up.sh), never a shared or production environment." >&2
    exit 1
    ;;
esac

: "${BACKEND_LOG:?set to the backend log path scripts/dev-up.sh printed - needed to read OTP codes back out}"
: "${DRIVER_PHONE:=+919900022233}"
: "${RIDER_PHONE:=+919900033344}"

for cmd in curl python3 mktemp; do
  command -v "$cmd" >/dev/null || { echo "Missing required command: $cmd" >&2; exit 1; }
done

SEED_IMAGE=""
cleanup() { [[ -n "$SEED_IMAGE" ]] && rm -f "$SEED_IMAGE"; }
trap cleanup EXIT

json_field() {
  python3 -c "import json,sys;print(json.load(sys.stdin)$1)"
}

# Fails loudly with the real response body - every curl call below that can hit a real business-rule
# rejection (not just transport-level failure) omits `-f` so the body is actually visible, and goes
# through this instead of relying on `set -e` to catch a non-2xx (which `-f` would otherwise make
# curl itself swallow the body for, confirmed the hard way: a prior version of this script exited
# silently right at a 403 rejection because `-f` discarded the response).
require_success() {
  local response="$1" context="$2"
  local ok
  ok=$(echo "$response" | python3 -c "import json,sys;print(json.load(sys.stdin).get('success'))" 2>/dev/null || echo "false")
  if [[ "$ok" != "True" ]]; then
    echo "$context failed: $response" >&2
    exit 1
  fi
}

otp_login() {
  local phone="$1" role="$2"
  curl -sf -X POST "$BASE_URL/api/v1/auth/otp/request" -H 'Content-Type: application/json' \
    -d "{\"phone\":\"$phone\",\"purpose\":\"LOGIN\",\"intendedRole\":\"$role\"}" >/dev/null
  sleep 1
  local code
  code=$(grep "phone=$phone" "$BACKEND_LOG" | tail -1 | grep -oE 'code=[0-9]+' | cut -d= -f2)
  if [[ -z "$code" ]]; then
    echo "Could not find a logged OTP code for $phone in $BACKEND_LOG" >&2
    exit 1
  fi
  curl -sf -X POST "$BASE_URL/api/v1/auth/otp/verify" -H 'Content-Type: application/json' \
    -d "{\"phone\":\"$phone\",\"code\":\"$code\",\"purpose\":\"LOGIN\",\"intendedRole\":\"$role\",\"deviceId\":\"seed-ride-offer\"}" \
    | json_field "['data']['accessToken']"
}

# Same pickup point used for both the driver's seeded location and the booking's pickup - trivially
# inside driver.search.radius.m (default 5000) regardless of its configured value, so this doesn't
# depend on that config staying at its default.
PICKUP_LAT=34.08
PICKUP_LNG=74.80
DEST_LAT=34.11
DEST_LNG=74.84

echo "== Logging the seeded driver in and refreshing their location =="
echo "   (expects the driver to already be ONLINE via the app - see the usage note at the top of this script)"
DRIVER_TOKEN=$(otp_login "$DRIVER_PHONE" "DRIVER")
# Idempotent either way (ONLINE -> ONLINE), but deliberately NOT relied upon to go OFFLINE -> ONLINE -
# only the app's own call, from a still-OFFLINE state, is guaranteed to succeed (see usage note).
curl -sf -X PUT "$BASE_URL/api/v1/drivers/me/availability" -H "Authorization: Bearer $DRIVER_TOKEN" \
  -H 'Content-Type: application/json' -d '{"status":"ONLINE"}' >/dev/null
RECORDED_AT=$(python3 -c "import datetime;print(datetime.datetime.now(datetime.timezone.utc).isoformat())")
curl -sf -X POST "$BASE_URL/api/v1/drivers/me/location" -H "Authorization: Bearer $DRIVER_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"latitude\":$PICKUP_LAT,\"longitude\":$PICKUP_LNG,\"recordedAt\":\"$RECORDED_AT\",\"accuracyMeters\":10}" >/dev/null
echo "Driver $DRIVER_PHONE location set to ($PICKUP_LAT, $PICKUP_LNG)."

echo "== Logging a rider in (creating the account on first OTP login, same as the driver) =="
RIDER_TOKEN=$(otp_login "$RIDER_PHONE" "CUSTOMER")

echo "== Accepting the rider self-declaration (RealEligibilityPolicy rejects booking creation without it) =="
DECLARATION_RESPONSE=$(curl -s -X POST "$BASE_URL/api/v1/customers/me/declaration" -H "Authorization: Bearer $RIDER_TOKEN" \
  -H 'Content-Type: application/json' -d '{"declarationVersion":"v1"}')
require_success "$DECLARATION_RESPONSE" "Accepting the rider declaration"

echo "== Capturing a throwaway ride selfie (a real, decodable PNG - the backend validates the image, not just the content-type header) =="
SEED_IMAGE="$(mktemp -t seed-ride-selfie-XXXXXX).png"
python3 - "$SEED_IMAGE" <<'PYEOF'
import struct, zlib, sys

def chunk(tag, data):
    return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data))

width, height = 20, 20
raw = b""
for _ in range(height):
    raw += b"\x00" + bytes([180, 140, 120] * width)
png = b"\x89PNG\r\n\x1a\n"
png += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
png += chunk(b"IDAT", zlib.compress(raw))
png += chunk(b"IEND", b"")
with open(sys.argv[1], "wb") as f:
    f.write(png)
PYEOF
RIDE_SELFIE_RESPONSE=$(curl -s -X POST "$BASE_URL/api/v1/customers/me/ride-selfies" \
  -H "Authorization: Bearer $RIDER_TOKEN" -F "file=@${SEED_IMAGE};type=image/png")
require_success "$RIDE_SELFIE_RESPONSE" "Capturing the ride selfie"
RIDE_SELFIE_ID=$(echo "$RIDE_SELFIE_RESPONSE" | json_field "['data']['rideSelfieId']")

echo "== Getting a fare estimate (expectedFare must match a fresh quote) =="
ESTIMATE_RESPONSE=$(curl -s -X POST "$BASE_URL/api/v1/bookings/estimate" -H "Authorization: Bearer $RIDER_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"pickupLatitude\":$PICKUP_LAT,\"pickupLongitude\":$PICKUP_LNG,\"destinationLatitude\":$DEST_LAT,\"destinationLongitude\":$DEST_LNG,\"vehicleType\":\"CAB\"}")
require_success "$ESTIMATE_RESPONSE" "Getting a fare estimate"
EXPECTED_FARE=$(echo "$ESTIMATE_RESPONSE" | json_field "['data']['estimatedFare']")

echo "== Creating the booking =="
IDEMPOTENCY_KEY=$(python3 -c "import uuid;print(uuid.uuid4())")
BOOKING_RESPONSE=$(curl -s -X POST "$BASE_URL/api/v1/bookings" -H "Authorization: Bearer $RIDER_TOKEN" \
  -H "Idempotency-Key: $IDEMPOTENCY_KEY" -H 'Content-Type: application/json' \
  -d "{\"pickupLatitude\":$PICKUP_LAT,\"pickupLongitude\":$PICKUP_LNG,\"pickupAddress\":\"Lal Chowk\",\
\"destinationLatitude\":$DEST_LAT,\"destinationLongitude\":$DEST_LNG,\"destinationAddress\":\"Hazratbal\",\
\"vehicleType\":\"CAB\",\"paymentMethod\":\"CASH\",\"expectedFare\":$EXPECTED_FARE,\"rideSelfieId\":\"$RIDE_SELFIE_ID\"}")
require_success "$BOOKING_RESPONSE" "Creating the booking"
BOOKING_ID=$(echo "$BOOKING_RESPONSE" | json_field "['data']['id']")

cat <<EOF

========================================================================
Booking $BOOKING_ID created for rider $RIDER_PHONE, pickup near
($PICKUP_LAT, $PICKUP_LNG) - should now be matched and pushed as a ride
offer to driver $DRIVER_PHONE over /user/queue/offers.

If the driver Android app was already logged in as $DRIVER_PHONE and
online (see this script's usage note), the offer should arrive there now.
========================================================================
EOF
