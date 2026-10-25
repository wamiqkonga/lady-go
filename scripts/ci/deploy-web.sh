#!/usr/bin/env bash
# Upload a built website to the MilesWeb host over HTTPS (cPanel Web Disk, i.e. WebDAV on port 2078), check the
# live site, and put the old page back if the check fails. (The host's FTP has no encryption, so FTP is not used.)
#
# Usage:  scripts/ci/deploy-web.sh <dist dir> <live site url>
#   e.g.  scripts/ci/deploy-web.sh frontend/website/dist https://ladygoride.com
# Environment: WEBDAV_HOST (e.g. emphasis.herosite.pro), WEBDAV_USER, WEBDAV_PASSWORD, optional WEBDAV_PORT (2078).
# The Web Disk account must be locked to this site's folder (it is created that way in cPanel), so the paths below
# are relative to the site root.
#
# Order matters: new files first (old ones are never deleted, so the live page keeps working), index.html last.
# .htaccess, .user.ini, php.ini and everything else on the host are never touched.
set -euo pipefail

DIST="${1:?usage: deploy-web.sh <dist dir> <live site url>}"
LIVE="${2:?usage: deploy-web.sh <dist dir> <live site url>}"
LIVE="${LIVE%/}"

: "${WEBDAV_HOST:?WEBDAV_HOST is not set}"
: "${WEBDAV_USER:?WEBDAV_USER is not set}"
: "${WEBDAV_PASSWORD:?WEBDAV_PASSWORD is not set}"
WEBDAV_PORT="${WEBDAV_PORT:-2078}"
DAV="https://$WEBDAV_HOST:$WEBDAV_PORT"

CURL="${CURL:-curl}"
API_URL="${API_URL:-https://api.ladygoride.com}"
VERIFY_TRIES="${VERIFY_TRIES:-6}"
VERIFY_WAIT="${VERIFY_WAIT:-5}"

log() { echo "deploy-web: $*"; }

WORK="$(mktemp -d)"
CONF="$WORK/curl.conf"
trap 'rm -rf "$WORK"' EXIT
# Credentials go in a private config file, not on the command line (where other processes could read them).
umask 077
# curl treats backslash and double quote inside a config value as escapes, so escape them.
esc() { printf '%s' "$1" | sed 's/[\\"]/\\&/g'; }
printf 'user = "%s:%s"\n' "$(esc "$WEBDAV_USER")" "$(esc "$WEBDAV_PASSWORD")" > "$CONF"

# Create the folders of a path on the host (a PUT does not create folders). 201 = created, 405 = already there.
MADE="$WORK/made-dirs"; : > "$MADE"
ensure_dirs() { # path of a file on the host
  local dir sofar="" part code
  dir="$(dirname "$1")"
  [ "$dir" = "." ] && return 0
  IFS=/ read -r -a parts <<< "$dir"
  for part in "${parts[@]}"; do
    sofar="${sofar:+$sofar/}$part"
    grep -qxF "$sofar" "$MADE" && continue
    code="$("$CURL" -sS --max-time 30 -K "$CONF" -X MKCOL -o /dev/null -w '%{http_code}' "$DAV/$sofar/" || true)"
    case "$code" in
      201|405|301) echo "$sofar" >> "$MADE" ;;
      *) log "could not create folder $sofar on the host (HTTP $code)"; return 1 ;;
    esac
  done
}
dav_put() { # local file, path on the host
  ensure_dirs "$2"
  "$CURL" -fsS --max-time 120 -K "$CONF" -T "$1" "$DAV/$2" >/dev/null
}
http_get() { # url, output file
  "$CURL" -fsS --max-time 30 -H 'Cache-Control: no-cache' -o "$2" "$1"
}

# 1. The build must be fit to publish. A build made without VITE_API_BASE_URL points at localhost; the code keeps the
#    text "localhost" as an unused fallback even in a good build, so check what matters: index.html (its security
#    policy) must not mention localhost, must allow the real API, and the built code must contain the real API address.
[ -f "$DIST/index.html" ] || { log "no $DIST/index.html; build first"; exit 1; }
if grep -q "localhost" "$DIST/index.html"; then
  log "index.html mentions localhost, so the build was not made with VITE_API_BASE_URL=$API_URL; refusing to publish"
  exit 1
fi
grep -q "connect-src 'self' $API_URL" "$DIST/index.html" || {
  log "the page's security policy does not allow $API_URL; refusing to publish"; exit 1; }
if ! grep -rIlF "$API_URL" "$DIST/assets" --include='*.js' >/dev/null 2>&1; then
  log "the built code never mentions $API_URL, so it was not built for production; refusing to publish"
  exit 1
fi

# The build must never carry host configuration: an .htaccess, .user.ini, php.ini or script in dist would overwrite the
# live routing, security headers or PHP settings of the site.
DANGEROUS="$(find "$DIST" -mindepth 1 \( -name '.*' -o -iname '*.php' -o -iname '*.ini' -o -iname '*.htaccess' \) ! -name '.DS_Store')"
if [ -n "$DANGEROUS" ]; then
  log "the build contains host configuration or script files, which are never uploaded; refusing to publish:"
  echo "$DANGEROUS" | sed 's/^/  /'
  exit 1
fi

# 2. Keep a copy of the page that is live now, for restoring.
PREV="$WORK/previous-index.html"
if http_get "$LIVE/index.html" "$PREV"; then
  log "saved the current live index.html"
else
  log "could not read the current live page (continuing without a restore copy)"
  rm -f "$PREV"
fi

restore() {
  if [ -f "$PREV" ]; then
    log "putting the previous index.html back"
    if dav_put "$PREV" "index.html"; then
      if http_get "$LIVE/index.html" "$WORK/restored-index.html" && cmp -s "$PREV" "$WORK/restored-index.html"; then
        log "restored: the previous page is live again"
      else
        log "WARNING: the previous page was uploaded but the live site does not show it yet; open $LIVE and check by hand"
      fi
    else
      log "RESTORE FAILED - the live page may be wrong; put index.html.prev back over index.html in cPanel File Manager"
    fi
  else
    log "no restore copy was saved; check $LIVE by hand"
  fi
}

# 3. Upload: everything except the top-level index.html first, then index.html.
FILES="$(cd "$DIST" && find . -type f ! -path ./index.html | sed 's#^\./##' | sort)"
COUNT=0
while IFS= read -r f; do
  [ -n "$f" ] || continue
  dav_put "$DIST/$f" "$f"
  COUNT=$((COUNT + 1))
done <<< "$FILES"
log "uploaded $COUNT supporting files"

[ -f "$PREV" ] && dav_put "$PREV" "index.html.prev"
if dav_put "$DIST/index.html" "index.html"; then
  log "uploaded index.html"
else
  # An upload that breaks half way can leave a truncated page behind.
  log "the index.html upload failed"
  restore
  exit 1
fi

# 4. Check the live site serves exactly what was built (the host may take a few seconds to show new files).
verify() {
  local f
  http_get "$LIVE/index.html" "$WORK/live-index.html" || return 1
  cmp -s "$DIST/index.html" "$WORK/live-index.html" || return 1
  for f in $(grep -oE '/assets/[A-Za-z0-9._-]+' "$DIST/index.html" | sort -u); do
    http_get "$LIVE$f" "$WORK/live-asset" || return 1
    cmp -s "$DIST$f" "$WORK/live-asset" || return 1
  done
  return 0
}

tries=0
until verify; do
  tries=$((tries + 1))
  if [ "$tries" -ge "$VERIFY_TRIES" ]; then
    log "the live site does not match the build after $tries checks"
    restore
    exit 1
  fi
  sleep "$VERIFY_WAIT"
done
log "ok: $LIVE serves the new build"
