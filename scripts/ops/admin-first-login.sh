#!/usr/bin/env bash
# One-time setup for a freshly bootstrapped admin: change the temporary password and enrol an authenticator
# app (TOTP). The admin web UI cannot do this yet, so it goes through the API
# (docs/development/auth-local-runbook.md sections 3-5). Safe to re-run: it resumes from whichever step
# the account still needs. Passwords are read with hidden input and are never printed or written to disk.
#
# Usage: scripts/ops/admin-first-login.sh admin@example.com
set -euo pipefail

API="${API:-https://api.ladygoride.com}"
SERVER="${SERVER:-ubuntu@13.205.89.61}"
EMAIL="${1:?usage: $0 admin-email}"

# post PATH BEARER_TOKEN_OR_EMPTY JSON_BODY_OR_EMPTY -> prints the response body
post() {
  local path="$1"
  local token="$2"
  local body="$3"
  local args=(-sS -X POST "$API$path" -H 'Content-Type: application/json')
  if [ -n "$token" ]; then args+=(-H "Authorization: Bearer $token"); fi
  if [ -n "$body" ]; then args+=(-d "$body"); fi
  curl "${args[@]}"
}

# field DOTTED.PATH -> reads JSON on stdin, prints the field; on an API error prints its message and exits
field() {
  FIELD_PATH="$1" python3 -c '
import json, os, sys
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit("Unexpected (non-JSON) response from the server.")
if not d.get("success"):
    e = d.get("error") or {}
    sys.exit("Server said: %s" % (e.get("message") or d.get("message") or "request failed"))
cur = d
for part in os.environ["FIELD_PATH"].split("."):
    cur = cur.get(part) if isinstance(cur, dict) else None
if cur is None:
    sys.exit("Response did not contain %s" % os.environ["FIELD_PATH"])
print(cur)
'
}

json_body() { # json_body key=ENVVAR ... -> JSON object, values read from the named environment variables
  python3 -c '
import json, os, sys
print(json.dumps({k: os.environ[v] for k, v in (a.split("=", 1) for a in sys.argv[1:])}))
' "$@"
}

clean() { printf '%s' "$1" | tr -d '\r' | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//'; }

read -r -s -p "Current password (the temporary one, or your new one if you already changed it; Enter = read the temporary one from the server): " PW; echo
if [ -z "$PW" ]; then
  echo "Reading the temporary password from $SERVER (nothing is displayed)..."
  PW="$(ssh -o BatchMode=yes -o ConnectTimeout=15 "$SERVER" "sed -n 's/^Temporary password: //p' /opt/ladygo/admin-bootstrap.txt")"
fi
PW="$(clean "$PW")"
echo "(password read: ${#PW} characters)"

echo "1/4 Signing in..."
export EMAIL PW
LOGIN="$(post /api/v1/auth/admin/login "" "$(json_body email=EMAIL password=PW)")"
TOKEN="$(printf '%s' "$LOGIN" | field data.enrolmentToken)"
STEPS="$(printf '%s' "$LOGIN" | field data.remainingSteps)"
echo "    steps still needed: $STEPS"

if [[ "$STEPS" == *PASSWORD_CHANGE* ]]; then
  read -r -s -p "New password (use a long one, 14+ characters): " NEW_PW; echo
  read -r -s -p "Repeat new password: " NEW_PW2; echo
  [ "$NEW_PW" = "$NEW_PW2" ] || { echo "The two new passwords do not match." >&2; exit 1; }
  NEW_PW="$(clean "$NEW_PW")"
  echo "2/4 Changing the password..."
  export CUR="$PW" NEWPW="$NEW_PW"
  post /api/v1/auth/admin/password/change "$TOKEN" "$(json_body currentPassword=CUR newPassword=NEWPW)" | field success >/dev/null
  # The old enrolment token was issued before the change; sign in again with the new password.
  export PW="$NEW_PW"
  LOGIN="$(post /api/v1/auth/admin/login "" "$(json_body email=EMAIL password=PW)")"
  TOKEN="$(printf '%s' "$LOGIN" | field data.enrolmentToken)"
else
  echo "2/4 Password already changed - skipping."
fi

echo "3/4 Starting authenticator enrolment..."
ENROLL="$(post /api/v1/auth/admin/mfa/enroll "$TOKEN" "")"
SECRET="$(printf '%s' "$ENROLL" | field data.secret)"
URI="$(printf '%s' "$ENROLL" | field data.provisioningUri)"

echo
echo "Add this account to your authenticator app (Google Authenticator, 1Password, Authy, ...)."
echo "If the app already has an old 'Lady Go' entry from an earlier attempt, delete it first - this key replaces it."
if command -v qrencode >/dev/null 2>&1; then
  qrencode -t ANSIUTF8 "$URI"
else
  echo "  (no QR tool installed - choose 'enter a setup key' in the app)"
fi
echo "  Account : $EMAIL"
echo "  Key     : $SECRET"
echo "  Type    : time-based (TOTP), 6 digits, 30 seconds"
echo

read -r -p "4/4 Enter the 6-digit code the app now shows: " CODE
CODE="$(clean "$CODE")"
export CODE
post /api/v1/auth/admin/mfa/enroll/confirm "$TOKEN" "$(json_body code=CODE)" | field success >/dev/null

echo
echo "Done. Sign in at https://admin.ladygoride.com with your email, your password and a FRESH app code"
echo "(wait for the code to change - the one used just now cannot be used again)."
echo "Now delete the temporary password file on the server:"
echo "  ssh $SERVER rm /opt/ladygo/admin-bootstrap.txt"
