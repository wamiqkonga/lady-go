#!/usr/bin/env bash
# Tests deploy-web.sh against a fake curl that behaves like the Web Disk host plus the live website.
# Run: bash scripts/ci/deploy-web.test.sh
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PASS=0
FAIL=0
API="https://api.ladygoride.com"

new_sandbox() {
  SANDBOX="$(mktemp -d)"
  mkdir -p "$SANDBOX/bin" "$SANDBOX/server/assets" "$SANDBOX/dist/assets" "$SANDBOX/dist/fonts"
  # What is live before the deploy: the old page and its old asset.
  echo "<html>OLD page uses /assets/old.js</html>" > "$SANDBOX/server/index.html"
  echo "old-js" > "$SANDBOX/server/assets/old.js"
  # The new build.
  cat > "$SANDBOX/dist/index.html" <<EOF
<html><head><meta http-equiv="Content-Security-Policy" content="default-src 'self'; connect-src 'self' $API"><script type="module" src="/assets/new.js"></script><link rel="stylesheet" href="/assets/new.css"></head></html>
EOF
  echo "const u = \"$API\" ?? \"http://localhost:8080\"" > "$SANDBOX/dist/assets/new.js"
  echo "new-css" > "$SANDBOX/dist/assets/new.css"
  echo "font" > "$SANDBOX/dist/fonts/f.ttf"
  : > "$SANDBOX/calls"
  # Fake curl: "-T file https://host:2078/path" stores into server/; any http(s) GET reads from server/.
  cat > "$SANDBOX/bin/curl" <<'EOF'
#!/usr/bin/env bash
args=("$@")
up=""; url=""; out=""; method=""
for ((i=0; i<${#args[@]}; i++)); do
  case "${args[$i]}" in
    -T) up="${args[$((i+1))]}" ;;
    -o) out="${args[$((i+1))]}" ;;
    -K) conf="${args[$((i+1))]}" ;;
    -X) method="${args[$((i+1))]}" ;;
    http://*|https://*) url="${args[$i]}" ;;
  esac
done
[ -n "${conf:-}" ] && cp "$conf" "$FAKE_ROOT/last-conf" 2>/dev/null
if [ "$method" = "MKCOL" ]; then
  path="${url#https://*/}"; path="${path%/}"
  echo "MKCOL $path" >> "$FAKE_ROOT/calls"
  [ -n "${FAKE_MKCOL_CODE:-}" ] && { echo -n "$FAKE_MKCOL_CODE"; exit 0; }
  if [ -d "$FAKE_ROOT/server/$path" ]; then echo -n 405; else mkdir -p "$FAKE_ROOT/server/$path"; echo -n 201; fi
  exit 0
fi
if [ -n "$up" ]; then
  path="${url#https://*/}"
  echo "UPLOAD $path" >> "$FAKE_ROOT/calls"
  [ "${FAKE_PUT_FAIL_ON:-}" = "$path" ] && exit 9
  mkdir -p "$FAKE_ROOT/server/$(dirname "$path")"
  cp "$up" "$FAKE_ROOT/server/$path"
  exit 0
fi
path="/${url#*://*/}"
echo "GET $path" >> "$FAKE_ROOT/calls"
if [ "${FAKE_STALE:-0}" = 1 ] && [ "$path" = "/index.html" ]; then
  cat "$FAKE_ROOT/server/index.html.prev" > "$out" 2>/dev/null || cat "$FAKE_ROOT/server/index.html" > "$out"
  # always the OLD page, as if the host kept serving a cached copy
  echo "<html>OLD page uses /assets/old.js</html>" > "$out"
  exit 0
fi
[ -f "$FAKE_ROOT/server$path" ] || exit 22
cp "$FAKE_ROOT/server$path" "$out"
EOF
  chmod +x "$SANDBOX/bin/curl"
}

run_deploy() {
  FAKE_ROOT="$SANDBOX" CURL="$SANDBOX/bin/curl" WEBDAV_HOST="host.example" WEBDAV_USER="deploy" WEBDAV_PASSWORD="s3cret-pass" \
    VERIFY_TRIES=2 VERIFY_WAIT=0 bash "$HERE/deploy-web.sh" "$SANDBOX/dist" "https://site.example" > "$SANDBOX/out" 2>&1
}

check() { # name, "ok" when the condition held
  if [ "$2" = ok ]; then PASS=$((PASS + 1)); echo "PASS $1"; else FAIL=$((FAIL + 1)); echo "FAIL $1"; sed 's/^/    /' "$SANDBOX/out"; fi
}

# 1. A good build goes live; files go up in the right order; the old assets stay; the old page is kept as .prev.
new_sandbox
FAKE_ROOT="$SANDBOX"; export FAKE_ROOT
run_deploy; rc=$?
last_upload="$(grep '^UPLOAD' "$SANDBOX/calls" | tail -1)"
[ $rc -eq 0 ] && [ "$last_upload" = "UPLOAD index.html" ] \
  && cmp -s "$SANDBOX/dist/index.html" "$SANDBOX/server/index.html" \
  && [ -f "$SANDBOX/server/assets/old.js" ] \
  && grep -q "OLD page" "$SANDBOX/server/index.html.prev" \
  && grep -q "UPLOAD assets/new.js" "$SANDBOX/calls" && grep -q "UPLOAD fonts/f.ttf" "$SANDBOX/calls"
check "good build goes live, index.html last, old files kept, old page saved" "$([ $? -eq 0 ] && echo ok)"

# 2. The host keeps serving the old page: the run fails and the old index.html is put back.
new_sandbox
FAKE_STALE=1 run_deploy; rc=$?
[ $rc -ne 0 ] && grep -q "putting the previous index.html back" "$SANDBOX/out" \
  && grep -q "OLD page" "$SANDBOX/server/index.html"
check "a live site that does not match the build fails and restores the old page" "$([ $? -eq 0 ] && echo ok)"

# 3. A build whose page points at localhost is refused before anything is uploaded.
new_sandbox
sed -i.bak "s#connect-src 'self' $API#connect-src 'self' http://localhost:8080#" "$SANDBOX/dist/index.html" && rm -f "$SANDBOX/dist/index.html.bak"
run_deploy; rc=$?
[ $rc -ne 0 ] && grep -q "localhost" "$SANDBOX/out" && ! grep -q '^UPLOAD' "$SANDBOX/calls"
check "a build whose page points at localhost is refused with nothing uploaded" "$([ $? -eq 0 ] && echo ok)"

# 3b. Built code that never mentions the real API (built without the setting) is refused.
new_sandbox
echo 'const u = "http://localhost:8080"' > "$SANDBOX/dist/assets/new.js"
run_deploy; rc=$?
[ $rc -ne 0 ] && grep -q "never mentions" "$SANDBOX/out" && ! grep -q '^UPLOAD' "$SANDBOX/calls"
check "built code without the real API address is refused" "$([ $? -eq 0 ] && echo ok)"

# 3c. A good build that merely keeps the localhost fallback text in its code is accepted.
new_sandbox
run_deploy; rc=$?
[ $rc -eq 0 ]
check "the localhost fallback text inside a good build does not block it" "$([ $? -eq 0 ] && echo ok)"

# 4. A page whose security policy does not allow the real API is refused.
new_sandbox
sed -i.bak "s#connect-src 'self' $API#connect-src 'self'#" "$SANDBOX/dist/index.html" && rm -f "$SANDBOX/dist/index.html.bak"
run_deploy; rc=$?
[ $rc -ne 0 ] && ! grep -q '^UPLOAD' "$SANDBOX/calls"
check "a build without the API in its security policy is refused" "$([ $? -eq 0 ] && echo ok)"

# 5. An upload that fails before index.html leaves the live page untouched.
new_sandbox
FAKE_PUT_FAIL_ON="assets/new.js" run_deploy; rc=$?
[ $rc -ne 0 ] && grep -q "OLD page" "$SANDBOX/server/index.html" && ! grep -q "UPLOAD index.html$" "$SANDBOX/calls"
check "a failed asset upload stops before index.html is touched" "$([ $? -eq 0 ] && echo ok)"

# 6. Missing credentials stop immediately.
new_sandbox
FAKE_ROOT="$SANDBOX" CURL="$SANDBOX/bin/curl" WEBDAV_HOST="" bash "$HERE/deploy-web.sh" "$SANDBOX/dist" "https://site.example" > "$SANDBOX/out" 2>&1; rc=$?
[ $rc -ne 0 ] && grep -q "WEBDAV_HOST" "$SANDBOX/out"
check "missing host settings are rejected" "$([ $? -eq 0 ] && echo ok)"

# 7. The password never appears in the log or on the fake curl's command line.
new_sandbox
run_deploy
! grep -q "s3cret-pass" "$SANDBOX/out" && ! grep -q "s3cret-pass" "$SANDBOX/calls"
check "the password is not printed or passed as an argument" "$([ $? -eq 0 ] && echo ok)"

# 8. If the index.html upload itself breaks, the old page is put back (a half-written upload can leave a broken page).
new_sandbox
FAKE_PUT_FAIL_ON="index.html" run_deploy; rc=$?
[ $rc -ne 0 ] && grep -q "the index.html upload failed" "$SANDBOX/out" && grep -q "putting the previous index.html back" "$SANDBOX/out"
check "a failed index.html upload tries to restore the old page" "$([ $? -eq 0 ] && echo ok)"

# 9. Host configuration in the build is refused before anything is uploaded.
for bad in .htaccess .user.ini php.ini evil.php; do
  new_sandbox
  echo "x" > "$SANDBOX/dist/$bad"
  run_deploy; rc=$?
  [ $rc -ne 0 ] && grep -q "host configuration" "$SANDBOX/out" && ! grep -q '^UPLOAD' "$SANDBOX/calls"
  check "a build containing $bad is refused with nothing uploaded" "$([ $? -eq 0 ] && echo ok)"
done

# 10. A nested index.html (not the top-level page) is a normal file and is uploaded.
new_sandbox
mkdir -p "$SANDBOX/dist/docs" && echo "doc" > "$SANDBOX/dist/docs/index.html"
run_deploy; rc=$?
[ $rc -eq 0 ] && grep -q "UPLOAD docs/index.html" "$SANDBOX/calls" \
  && [ "$(grep -n 'MKCOL docs' "$SANDBOX/calls" | head -1 | cut -d: -f1)" -lt "$(grep -n 'UPLOAD docs/index.html' "$SANDBOX/calls" | head -1 | cut -d: -f1)" ]
check "a nested index.html is uploaded like any other file, after its folder is created" "$([ $? -eq 0 ] && echo ok)"

# 11. A password with a backslash and a double quote reaches curl unchanged (curl reads escapes in its config file).
new_sandbox
FAKE_ROOT="$SANDBOX" CURL="$SANDBOX/bin/curl" WEBDAV_HOST="host.example" WEBDAV_USER="deploy" WEBDAV_PASSWORD='pa\ss"w$rd' \
  VERIFY_TRIES=1 VERIFY_WAIT=0 bash "$HERE/deploy-web.sh" "$SANDBOX/dist" "https://site.example" > "$SANDBOX/out" 2>&1; rc=$?
decoded="$(python3 -c "
import re,sys
line=open('$SANDBOX/last-conf').read().strip()
m=re.fullmatch(r'user = \"(.*)\"',line)
v=m.group(1)
# curl config: backslash-escaped characters stand for themselves
print(re.sub(r'\\\\(.)',r'\\1',v))
")"
[ $rc -eq 0 ] && [ "$decoded" = 'deploy:pa\ss"w$rd' ]
check "a password with a backslash and a quote reaches curl unchanged" "$([ $? -eq 0 ] && echo ok)"

# 12. A folder the host refuses to create stops the run before the live page is touched.
new_sandbox
mkdir -p "$SANDBOX/dist/newdir" && echo "x" > "$SANDBOX/dist/newdir/a.txt"
FAKE_MKCOL_CODE=403 run_deploy; rc=$?
[ $rc -ne 0 ] && grep -q "could not create folder" "$SANDBOX/out" && grep -q "OLD page" "$SANDBOX/server/index.html"
check "a folder the host refuses to create stops the run with the live page untouched" "$([ $? -eq 0 ] && echo ok)"

# 13. A folder that already exists (the host answers 405) is fine.
new_sandbox
FAKE_MKCOL_CODE=405 run_deploy; rc=$?
[ $rc -eq 0 ]
check "folders that already exist on the host are accepted" "$([ $? -eq 0 ] && echo ok)"

echo "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
