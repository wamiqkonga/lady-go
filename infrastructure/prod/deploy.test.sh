#!/usr/bin/env bash
# Tests deploy.sh with fake docker/curl/sleep so no real server is needed. Run: bash infrastructure/prod/deploy.test.sh
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PASS=0
FAIL=0

new_sandbox() {
  SANDBOX="$(mktemp -d)"
  mkdir -p "$SANDBOX/bin" "$SANDBOX/ladygo/stack"
  echo "SECRET=1" > "$SANDBOX/ladygo/.env"
  echo "services: {}" > "$SANDBOX/ladygo/stack/docker-compose.yml"
  echo "BACKEND_IMAGE=ladygo-backend:old" > "$SANDBOX/ladygo/stack/image.env"
  printf '#!/usr/bin/env bash\necho backup-ran >> "%s/calls"\nexit "${FAKE_BACKUP_EXIT:-0}"\n' "$SANDBOX" > "$SANDBOX/ladygo/backup-db.sh"
  # docker: record the call; "compose ... up" records which image was selected.
  cat > "$SANDBOX/bin/docker" <<EOF
#!/usr/bin/env bash
echo "docker \$*" >> "$SANDBOX/calls"
case "\$*" in
  *" up "*) [ "\${FAKE_UP_FAIL:-0}" = 1 ] && grep -q new "$SANDBOX/ladygo/stack/image.env" && exit 1 ;;
  *" config "*) [ "\${FAKE_CONFIG_FAIL:-0}" = 1 ] && exit 1 ;;
esac
if [ "\$1" = compose ]; then
  echo "image-at-up=\$(grep BACKEND_IMAGE "$SANDBOX/ladygo/stack/image.env")" >> "$SANDBOX/calls"
fi
exit 0
EOF
  # curl: health is always UP; /actuator/info reports the sha in FAKE_LIVE_SHA, but only once the new image is selected.
  cat > "$SANDBOX/bin/curl" <<EOF
#!/usr/bin/env bash
[ "\${FAKE_ALL_DOWN:-0}" = 1 ] && exit 22
case "\$*" in
  *actuator/health*) echo '{"status":"UP"}' ;;
  *actuator/info*)
    if grep -q "new" "$SANDBOX/ladygo/stack/image.env"; then echo "{\"app\":{\"commit\":\"\${FAKE_NEW_SHA:-abc123}\"}}"; else echo '{"app":{"commit":"oldsha"}}'; fi ;;
esac
EOF
  printf '#!/usr/bin/env bash\nexit 0\n' > "$SANDBOX/bin/sleep"
  chmod +x "$SANDBOX"/bin/* "$SANDBOX/ladygo/backup-db.sh"
}

run_deploy() {
  PATH="$SANDBOX/bin:$PATH" COMPOSE_DIR="$SANDBOX/ladygo/stack" ENV_FILE="$SANDBOX/ladygo/.env" BACKUP_SCRIPT="$SANDBOX/ladygo/backup-db.sh" \
    HEALTH_TIMEOUT=6 LOCK_FILE="$SANDBOX/lock" bash "$HERE/deploy.sh" "$@" > "$SANDBOX/out" 2>&1
}

check() { # name, condition result
  if [ "$2" = ok ]; then PASS=$((PASS + 1)); echo "PASS $1"; else FAIL=$((FAIL + 1)); echo "FAIL $1"; sed 's/^/    /' "$SANDBOX/out"; fi
}

# 1. Healthy new version goes live, backup ran before it, previous image remembered.
new_sandbox
run_deploy "registry/ladygo-backend:new" abc123; rc=$?
[ $rc -eq 0 ] && grep -q "BACKEND_IMAGE=registry/ladygo-backend:new" "$SANDBOX/ladygo/stack/image.env" \
  && grep -q "ladygo-backend:old" "$SANDBOX/ladygo/stack/image.env.prev" \
  && [ "$(grep -n backup-ran "$SANDBOX/calls" | head -1 | cut -d: -f1)" -lt "$(grep -n 'compose' "$SANDBOX/calls" | head -1 | cut -d: -f1)" ]
check "healthy deploy goes live after a backup" "$([ $? -eq 0 ] && echo ok)"

# 2. New version never reports the expected sha: rolled back to old image, exit code non-zero.
new_sandbox
FAKE_NEW_SHA=wrongsha run_deploy "registry/ladygo-backend:new" abc123; rc=$?
[ $rc -ne 0 ] && grep -q "BACKEND_IMAGE=ladygo-backend:old" "$SANDBOX/ladygo/stack/image.env" && grep -q "rolled back" "$SANDBOX/out"
check "unhealthy new version is rolled back and the run fails" "$([ $? -eq 0 ] && echo ok)"

# 3. Backup failure stops the deploy before anything changes.
new_sandbox
FAKE_BACKUP_EXIT=1 run_deploy "registry/ladygo-backend:new" abc123; rc=$?
[ $rc -ne 0 ] && grep -q "BACKEND_IMAGE=ladygo-backend:old" "$SANDBOX/ladygo/stack/image.env" && ! grep -q "docker pull" "$SANDBOX/calls"
check "failed backup blocks the deploy" "$([ $? -eq 0 ] && echo ok)"

# 4. ECR images trigger a registry login; other images do not.
new_sandbox
run_deploy "332053469094.dkr.ecr.ap-south-1.amazonaws.com/ladygo-backend:new" abc123
grep -q "docker login --username AWS" "$SANDBOX/calls"
check "ECR image logs in to the registry" "$([ $? -eq 0 ] && echo ok)"

# 5. Missing arguments fail loudly.
new_sandbox
run_deploy; rc=$?
[ $rc -ne 0 ]
check "missing arguments are rejected" "$([ $? -eq 0 ] && echo ok)"

# 6. Redeploying the image that is already selected but unhealthy must not make it its own rollback target.
new_sandbox
echo "BACKEND_IMAGE=registry/ladygo-backend:new" > "$SANDBOX/ladygo/stack/image.env"
echo "BACKEND_IMAGE=ladygo-backend:old" > "$SANDBOX/ladygo/stack/image.env.prev"
FAKE_NEW_SHA=wrongsha run_deploy "registry/ladygo-backend:new" abc123; rc=$?
[ $rc -ne 0 ] && grep -q "BACKEND_IMAGE=ladygo-backend:old" "$SANDBOX/ladygo/stack/image.env" \
  && grep -q "BACKEND_IMAGE=ladygo-backend:old" "$SANDBOX/ladygo/stack/image.env.prev"
check "redeploying a bad current image still rolls back to the real previous image" "$([ $? -eq 0 ] && echo ok)"

# 7. compose failing to start the new container also rolls back (production never stays down).
new_sandbox
FAKE_UP_FAIL=1 run_deploy "registry/ladygo-backend:new" abc123; rc=$?
[ $rc -ne 0 ] && grep -q "BACKEND_IMAGE=ladygo-backend:old" "$SANDBOX/ladygo/stack/image.env" && grep -q "rolled back" "$SANDBOX/out"
check "compose start failure is rolled back" "$([ $? -eq 0 ] && echo ok)"

# 8. If the rollback is unhealthy too, it says a human is needed.
new_sandbox
FAKE_ALL_DOWN=1 run_deploy "registry/ladygo-backend:new" abc123; rc=$?
[ $rc -ne 0 ] && grep -q "needs a human" "$SANDBOX/out"
check "failed rollback is reported loudly" "$([ $? -eq 0 ] && echo ok)"

# 9. A shipped compose file that does not validate changes nothing.
new_sandbox
echo "new compose" > "$SANDBOX/ladygo/stack/docker-compose.yml.new"
FAKE_CONFIG_FAIL=1 run_deploy "registry/ladygo-backend:new" abc123; rc=$?
[ $rc -ne 0 ] && grep -q "BACKEND_IMAGE=ladygo-backend:old" "$SANDBOX/ladygo/stack/image.env" && grep -q "services" "$SANDBOX/ladygo/stack/docker-compose.yml"
check "invalid compose file is rejected without changes" "$([ $? -eq 0 ] && echo ok)"

# 10. A valid shipped compose file is swapped in, with the old one kept for rollback.
new_sandbox
echo "new compose" > "$SANDBOX/ladygo/stack/docker-compose.yml.new"
run_deploy "registry/ladygo-backend:new" abc123; rc=$?
[ $rc -eq 0 ] && grep -q "new compose" "$SANDBOX/ladygo/stack/docker-compose.yml" && grep -q "services" "$SANDBOX/ladygo/stack/docker-compose.yml.prev"
check "valid compose file is swapped in and the old one kept" "$([ $? -eq 0 ] && echo ok)"

echo "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
