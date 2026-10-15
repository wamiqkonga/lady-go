#!/usr/bin/env bash
# Approves the driver scripts/dev-up.sh already seeds (phone +919900022233, vehicle, declaration,
# 5 pending documents) all the way to APPROVED, through the real admin review APIs - so the driver
# Android app's "go online" toggle has something to actually succeed against when manually tested
# in the emulator. Does NOT create the driver itself (dev-up.sh already does that); this is a
# companion step, not a replacement. No direct database access anywhere in this script - the
# driver's id is resolved through GET /admin/driver-documents (the real pending-review queue), not
# a SQL lookup, since dev-up.sh deliberately never exposes its throwaway Postgres password after
# startup for a later script to use.
#
# Dev-only guard: refuses to run against anything that doesn't look like a local backend, same spirit
# as dev-up.sh's SPRING_PROFILES_ACTIVE check and the CI e2e-seed scripts' ephemeral-environment-only
# design - this mutates driver verification state via a real admin account and must never be pointed
# at a shared or production environment.
#
# Usage: run scripts/dev-up.sh first, copy the SUPER_ADMIN email/password/TOTP secret it prints, then:
#   ADMIN_EMAIL=... ADMIN_PASSWORD=... ADMIN_TOTP_SECRET=... scripts/dev/approve-seeded-driver.sh
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
case "$BASE_URL" in
  http://localhost:*|http://127.0.0.1:*) ;;
  *)
    echo "BASE_URL ('$BASE_URL') does not look like a local backend. Refusing to run - this script" >&2
    echo "mutates real driver verification state via an admin account and must only ever target a" >&2
    echo "throwaway local dev stack (scripts/dev-up.sh), never a shared or production environment." >&2
    exit 1
    ;;
esac

: "${ADMIN_EMAIL:?set to the SUPER_ADMIN email scripts/dev-up.sh printed}"
: "${ADMIN_PASSWORD:?set to the SUPER_ADMIN password scripts/dev-up.sh printed}"
: "${ADMIN_TOTP_SECRET:?set to the SUPER_ADMIN TOTP secret scripts/dev-up.sh printed}"
: "${DRIVER_PHONE:=+919900022233}"

for cmd in curl python3; do
  command -v "$cmd" >/dev/null || { echo "Missing required command: $cmd" >&2; exit 1; }
done

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

json_field() {
  python3 -c "import json,sys;print(json.load(sys.stdin)$1)"
}

echo "== Logging in as $ADMIN_EMAIL =="
# dev-up.sh's admin is already fully MFA-enrolled, so one call with totpCode included is enough
# (AdminAuthController.login's AdminLoginOutcome.Authenticated path) - no separate enrolment step.
CODE=$(totp_code "$ADMIN_TOTP_SECRET")
LOGIN=$(curl -sf -X POST "$BASE_URL/api/v1/auth/admin/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\",\"totpCode\":\"$CODE\"}")
ACCESS_TOKEN=$(echo "$LOGIN" | json_field "['data']['accessToken']")
if [[ -z "$ACCESS_TOKEN" || "$ACCESS_TOKEN" == "None" ]]; then
  echo "Admin login did not return an access token: $LOGIN" >&2
  exit 1
fi
AUTH_HEADER="Authorization: Bearer $ACCESS_TOKEN"

echo "== Finding the seeded driver's id via the pending-review queue =="
PENDING=$(curl -sf "$BASE_URL/api/v1/admin/driver-documents?size=200" -H "$AUTH_HEADER")
LAST4="${DRIVER_PHONE: -4}"
DRIVER_ID=$(echo "$PENDING" | python3 -c "
import json, sys
last4 = sys.argv[1]
page = json.load(sys.stdin)['data']
matches = {d['driverId'] for d in page['content'] if d.get('driverPhoneMasked', '').endswith(last4)}
if len(matches) == 1:
    print(matches.pop())
" "$LAST4")
if [[ -z "$DRIVER_ID" ]]; then
  echo "Could not find exactly one pending driver matching phone ending in $LAST4 - run" >&2
  echo "scripts/dev-up.sh first, or pass DRIVER_PHONE if you seeded a different one." >&2
  exit 1
fi
echo "Driver id: $DRIVER_ID"

echo "== Fetching the review summary =="
REVIEW=$(curl -sf "$BASE_URL/api/v1/admin/drivers/$DRIVER_ID/review" -H "$AUTH_HEADER")

echo "== Approving all pending documents =="
echo "$REVIEW" | python3 -c "
import json, sys
docs = json.load(sys.stdin)['data']['documents']
for d in docs:
    if d['status'] == 'PENDING':
        print(d['id'])
" | while read -r DOC_ID; do
  [[ -z "$DOC_ID" ]] && continue
  curl -sf -X POST "$BASE_URL/api/v1/admin/driver-documents/$DOC_ID/approve" \
    -H "$AUTH_HEADER" -H 'Content-Type: application/json' -d '{}' >/dev/null
  echo "Approved document $DOC_ID"
done

VEHICLE_ID=$(echo "$REVIEW" | json_field "['data']['vehicle']['id']")
echo "== Approving vehicle $VEHICLE_ID =="
curl -sf -X POST "$BASE_URL/api/v1/admin/vehicles/$VEHICLE_ID/approve" -H "$AUTH_HEADER" >/dev/null

echo "== Approving driver $DRIVER_ID =="
curl -sf -X POST "$BASE_URL/api/v1/admin/drivers/$DRIVER_ID/approve" -H "$AUTH_HEADER" >/dev/null

echo "== Confirming =="
FINAL_STATUS=$(curl -sf "$BASE_URL/api/v1/admin/drivers/$DRIVER_ID/review" -H "$AUTH_HEADER" \
  | json_field "['data']['verificationStatus']")
if [[ "$FINAL_STATUS" != "APPROVED" ]]; then
  echo "Driver status is '$FINAL_STATUS', not APPROVED - something didn't go through." >&2
  exit 1
fi

cat <<EOF

========================================================================
Driver $DRIVER_PHONE is APPROVED with an approved vehicle.

Use this phone number to log in from the driver Android app (emulator
API base URL is http://10.0.2.2:8080 by default, matching the backend
dev-up.sh just started) - the "Go online" toggle should now succeed.
========================================================================
EOF
