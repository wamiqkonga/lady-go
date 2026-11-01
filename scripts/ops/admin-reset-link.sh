#!/usr/bin/env bash
# Break-glass admin password recovery: use when the emailed "Forgot password?" link cannot be used (email provider
# down, address no longer reachable). Run it ON THE SERVER, as the owner. It issues a normal one-time reset link
# directly in the database and prints it; nobody's password or hash ever passes through this script. The link goes
# through the same /reset-password page, so a TOTP code is still required if the account has an authenticator.
#
# Usage:   scripts/ops/admin-reset-link.sh admin@example.com --confirm admin@example.com
# Needs:   psql, openssl; database access via the standard PG* variables (PGHOST, PGDATABASE, PGUSER, PGPASSWORD) or
#          a PSQL="psql <connection options>" override; LADYGO_ADMIN_WEB_BASE_URL (e.g. https://admin.example.com).
# Effects: closes any open reset links for that admin, creates one valid for 30 minutes, clears that admin's failed-login
#          wait (so a locked-out admin can use the link straight away), writes an audit entry.
set -euo pipefail

EMAIL="${1:-}"
CONFIRM=""
if [ "${2:-}" = "--confirm" ]; then CONFIRM="${3:-}"; fi
if [ -z "$EMAIL" ] || [ "$CONFIRM" != "$EMAIL" ]; then
  echo "usage: $0 admin-email --confirm admin-email   (the address twice, so a typo cannot reset the wrong account)" >&2
  exit 2
fi
if ! [[ "$EMAIL" =~ ^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+$ ]]; then
  echo "That does not look like a plain email address." >&2
  exit 2
fi

BASE="${LADYGO_ADMIN_WEB_BASE_URL:?set LADYGO_ADMIN_WEB_BASE_URL to the admin web address, e.g. https://admin.example.com}"
BASE="${BASE%/}"
# https anywhere; plain http only for exactly localhost / 127.0.0.1 (not look-alikes such as localhost.evil.com).
if ! [[ "$BASE" =~ ^https://[^/]+ || "$BASE" =~ ^http://(localhost|127\.0\.0\.1)(:[0-9]+)?(/|$) ]]; then
  echo "LADYGO_ADMIN_WEB_BASE_URL must be https." >&2; exit 2
fi
PSQL="${PSQL:-psql}"

# 32 random bytes, base64url without padding (43 chars); the database stores only its SHA-256, exactly as the app does.
TOKEN="$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n')"
HASH="$(printf '%s' "$TOKEN" | openssl dgst -sha256 -hex | sed 's/^.*= //')"

# -v ON_ERROR_STOP so a failure never half-applies; one transaction; the email is passed as a psql variable, not spliced.
OPERATOR="$(id -un)@$(hostname)"
RESULT="$($PSQL -X -q -t -A -v ON_ERROR_STOP=1 -v "email=$EMAIL" -v "hash=$HASH" -v "operator=$OPERATOR" <<'SQL'
BEGIN;
CREATE TEMP TABLE target ON COMMIT DROP AS
  SELECT id, role FROM users
  WHERE lower(email) = lower(:'email') AND role IN ('ADMIN', 'SUPER_ADMIN', 'SUPPORT') AND status = 'ACTIVE';
-- Exactly one account or nothing: if two rows ever shared an address, no link is created for either.
DO $$ BEGIN
  IF (SELECT count(*) FROM target) > 1 THEN RAISE EXCEPTION 'more than one active admin has that email; nothing changed'; END IF;
END $$;
UPDATE admin_password_resets SET used_at = now()
  WHERE used_at IS NULL AND user_id IN (SELECT id FROM target);
INSERT INTO admin_password_resets (user_id, token_hash, expires_at)
  SELECT id, :'hash', now() + interval '30 minutes' FROM target;
DELETE FROM admin_login_throttle WHERE email IN (SELECT lower(email) FROM users WHERE id IN (SELECT id FROM target));
DELETE FROM auth_rate_limits WHERE bucket_key IN (SELECT 'admin_login_email:' || lower(email) FROM users WHERE id IN (SELECT id FROM target));
INSERT INTO audit_logs (actor_id, actor_role, action, target_type, target_id, metadata)
  SELECT NULL, NULL, 'ADMIN_PASSWORD_RESET_LINK_ISSUED', 'users', id::text, jsonb_build_object('via', 'break-glass script', 'operator', :'operator') FROM target;
SELECT count(*) FROM target;
COMMIT;
SQL
)"

if [ "$RESULT" != "1" ]; then
  echo "No active admin account with that email, so nothing was changed." >&2
  exit 1
fi

echo "Reset link (valid 30 minutes, works once; open it in a browser):"
echo "${BASE}/reset-password#token=${TOKEN}"
echo "Handle it like a password: do not paste it into chat or tickets."
