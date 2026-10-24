#!/usr/bin/env bash
# Stores the SMTP email-provider settings (default: Resend) in /opt/ladygo/.env on the production server.
# The API key / password is read with hidden input, sent to the server over SSH on stdin (never on a command line)
# and is never printed or written anywhere else. It does NOT restart the backend: the new settings only take effect
# when the backend is restarted/redeployed, so you can run this safely at any time.
#
# Usage: scripts/ops/set-smtp-secret.sh                (Resend defaults)
#        SMTP_HOST=smtp-relay.brevo.com SMTP_USERNAME=you@example.com scripts/ops/set-smtp-secret.sh
set -euo pipefail

SERVER="${SERVER:-ubuntu@13.205.89.61}"
ENV_DIR="${ENV_DIR:-/opt/ladygo}"   # where .env lives on the server (overridden only for local dry-run tests)
SMTP_HOST="${SMTP_HOST:-smtp.resend.com}"
SMTP_PORT="${SMTP_PORT:-587}"
SMTP_USERNAME="${SMTP_USERNAME:-resend}"

read -r -s -p "SMTP password / API key for $SMTP_HOST (input is hidden): " SECRET; echo
SECRET="$(printf '%s' "$SECRET" | tr -d '\r\n' | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
[ -n "$SECRET" ] || { echo "Nothing entered - aborting." >&2; exit 1; }
echo "(read ${#SECRET} characters)"

# The remote command reads the secret from stdin; the host/port/username are not secret and travel in the command.
remote() {
  if [ -n "${DRY_RUN:-}" ]; then bash -c "$1"; else ssh -o BatchMode=yes -o ConnectTimeout=15 "$SERVER" "$1"; fi
}
printf '%s\n' "$SECRET" | remote "
set -e
read -r SECRET
cd $ENV_DIR
cp .env .env.bak-smtp
umask 077
grep -v -E '^LADYGO_(SMTP_(HOST|PORT|USERNAME|PASSWORD)|EMAIL_PROVIDER)=' .env > .env.new
{
  echo 'LADYGO_EMAIL_PROVIDER=smtp'
  echo 'LADYGO_SMTP_HOST=$SMTP_HOST'
  echo 'LADYGO_SMTP_PORT=$SMTP_PORT'
  echo 'LADYGO_SMTP_USERNAME=$SMTP_USERNAME'
  printf 'LADYGO_SMTP_PASSWORD=%s\n' \"\$SECRET\"
} >> .env.new
mv .env.new .env
chmod 600 .env
rm -f .env.bak-smtp
echo 'Saved. Keys now present in .env:'
grep -E '^LADYGO_(EMAIL_PROVIDER|EMAIL_FROM|SMTP_HOST|SMTP_PORT|SMTP_USERNAME|SMTP_PASSWORD)=' .env | sed -E 's/^(LADYGO_SMTP_PASSWORD)=.*/\1=********/'
"
unset SECRET
echo
echo "The backend still uses the old settings until it is restarted with the new build."
