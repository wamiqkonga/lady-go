#!/usr/bin/env bash
# Deploy one backend image on the production host, with a database backup first and an automatic rollback.
# Usage:  deploy.sh <full image reference> <git sha that image was built from>
# Run by the deploy workflow through AWS SSM (as root), or by hand over SSH:
#   sudo /opt/ladygo/deploy.sh 332053469094.dkr.ecr.ap-south-1.amazonaws.com/ladygo-backend:<sha> <sha>
#
# Host layout: /opt/ladygo/.env (secrets), /opt/ladygo/backup-db.sh, /opt/ladygo/stack/docker-compose.yml.
# State kept in $COMPOSE_DIR (/opt/ladygo/stack):
#   image.env             BACKEND_IMAGE=<image now running>
#   image.env.prev        BACKEND_IMAGE=<the last image that was running before it>; never equal to image.env
#   docker-compose.yml.new   optional: compose file shipped by the workflow, validated then swapped in after the backup
#   docker-compose.yml.prev  the compose file that was replaced (restored on rollback)
# Only the backend container is replaced; postgres, redis and caddy are never touched.
set -euo pipefail

IMAGE="${1:?usage: deploy.sh <image> <git-sha>}"
SHA="${2:?usage: deploy.sh <image> <git-sha>}"

COMPOSE_DIR="${COMPOSE_DIR:-/opt/ladygo/stack}"
ENV_FILE="${ENV_FILE:-/opt/ladygo/.env}"
BACKUP_SCRIPT="${BACKUP_SCRIPT:-/opt/ladygo/backup-db.sh}"
LOCK_FILE="${LOCK_FILE:-/opt/ladygo/deploy.lock}"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:8080}"
# Generous on purpose: a small host starts Spring Boot and runs database migrations in this time. Rolling back
# in the middle of a migration would leave the Liquibase lock set and break the old version too.
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-300}"
REGION="${AWS_REGION:-ap-south-1}"
# Same pinned digest as backup-db.sh (the container uses the instance role's credentials).
AWS_CLI_IMAGE="${AWS_CLI_IMAGE:-amazon/aws-cli@sha256:efe32e825cf203652eaca3e7e94679580274e7d567c39270aaa36a5144737108}"

log() { echo "$(date -u +%FT%TZ) deploy: $*"; }
compose() {
  docker compose -f "$COMPOSE_DIR/docker-compose.yml" --env-file "$ENV_FILE" --env-file "$COMPOSE_DIR/image.env" "$@"
}

# Wait until the backend reports UP and (when a sha is given) is the build we expect.
wait_healthy() {
  local expected_sha="$1" waited=0
  while [ "$waited" -lt "$HEALTH_TIMEOUT" ]; do
    if curl -fsS --max-time 5 "$HEALTH_URL/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
      if [ -z "$expected_sha" ] || curl -fsS --max-time 5 "$HEALTH_URL/actuator/info" 2>/dev/null | grep -q "\"commit\":\"$expected_sha\""; then
        return 0
      fi
    fi
    sleep 3
    waited=$((waited + 3))
  done
  return 1
}

# Put the previous image (and compose file) back. Never aborts half way and always says what happened.
rollback() {
  local reason="$1"
  set +e
  log "$reason; rolling back to ${ROLLBACK_LINE#BACKEND_IMAGE=}"
  echo "$ROLLBACK_LINE" > "$COMPOSE_DIR/image.env"
  [ -f "$COMPOSE_DIR/docker-compose.yml.prev" ] && cp "$COMPOSE_DIR/docker-compose.yml.prev" "$COMPOSE_DIR/docker-compose.yml"
  compose up -d --no-deps backend
  if wait_healthy ""; then
    log "rolled back; previous version is serving again"
  else
    log "ROLLBACK ALSO UNHEALTHY - needs a human. Check: docker logs ladygo-backend-1"
    log "If the log says the Liquibase changelog is locked: UPDATE databasechangeloglock SET locked=false; (see README)"
  fi
  exit 1
}

# Only one deploy at a time on this host (a hand-run deploy over SSH, or an SSM run still going).
if command -v flock >/dev/null 2>&1; then
  exec 9>"$LOCK_FILE"
  flock -n 9 || { log "another deploy is running; stopping"; exit 1; }
fi

[ -f "$ENV_FILE" ] || { log "missing $ENV_FILE"; exit 1; }
[ -f "$COMPOSE_DIR/docker-compose.yml" ] || { log "missing $COMPOSE_DIR/docker-compose.yml"; exit 1; }

# First deploy ever: remember the manually loaded image so a rollback has somewhere to go.
if [ ! -f "$COMPOSE_DIR/image.env" ]; then
  CURRENT="$(docker inspect --format '{{.Config.Image}}' ladygo-backend-1 2>/dev/null || true)"
  [ -n "$CURRENT" ] || { log "no image.env and no running backend to take the current image from"; exit 1; }
  echo "BACKEND_IMAGE=$CURRENT" > "$COMPOSE_DIR/image.env"
fi
CURRENT_LINE="$(cat "$COMPOSE_DIR/image.env")"
NEW_LINE="BACKEND_IMAGE=$IMAGE"

if [ "$CURRENT_LINE" = "$NEW_LINE" ]; then
  # Re-deploying the image that is already selected: keep the real previous image as the rollback target.
  if wait_healthy "$SHA"; then log "ok: $IMAGE is already live"; exit 0; fi
  log "$IMAGE is selected but not healthy, redeploying it"
  [ -f "$COMPOSE_DIR/image.env.prev" ] || { log "no previous image recorded to roll back to; fix by hand"; exit 1; }
  ROLLBACK_LINE="$(cat "$COMPOSE_DIR/image.env.prev")"
else
  ROLLBACK_LINE="$CURRENT_LINE"
fi

log "backing up the database"
[ -x "$BACKUP_SCRIPT" ] || { log "backup script $BACKUP_SCRIPT not found or not executable; refusing to deploy without a backup"; exit 1; }
"$BACKUP_SCRIPT" || { log "backup failed; refusing to deploy"; exit 1; }

case "$IMAGE" in
  *.amazonaws.com/*)
    REGISTRY="${IMAGE%%/*}"
    log "logging in to $REGISTRY"
    docker run --rm "$AWS_CLI_IMAGE" ecr get-login-password --region "$REGION" \
      | docker login --username AWS --password-stdin "$REGISTRY" >/dev/null
    ;;
esac

log "pulling $IMAGE"
docker pull "$IMAGE" >/dev/null

# A compose file shipped with this deploy is checked before it replaces the working one.
if [ -f "$COMPOSE_DIR/docker-compose.yml.new" ]; then
  echo "$NEW_LINE" > "$COMPOSE_DIR/image.env.check"
  if ! docker compose -f "$COMPOSE_DIR/docker-compose.yml.new" --env-file "$ENV_FILE" --env-file "$COMPOSE_DIR/image.env.check" config -q; then
    rm -f "$COMPOSE_DIR/docker-compose.yml.new" "$COMPOSE_DIR/image.env.check"
    log "the new compose file is not valid; nothing was changed"
    exit 1
  fi
  rm -f "$COMPOSE_DIR/image.env.check"
  cp "$COMPOSE_DIR/docker-compose.yml" "$COMPOSE_DIR/docker-compose.yml.prev"
  mv "$COMPOSE_DIR/docker-compose.yml.new" "$COMPOSE_DIR/docker-compose.yml"
fi

# Only now change state. image.env.prev is the image we can go back to and is never the same as image.env.
[ "$ROLLBACK_LINE" = "$NEW_LINE" ] || echo "$ROLLBACK_LINE" > "$COMPOSE_DIR/image.env.prev"
echo "$NEW_LINE" > "$COMPOSE_DIR/image.env"

log "starting the new backend"
compose up -d --no-deps backend || rollback "compose could not start the new backend"

if wait_healthy "$SHA"; then
  log "ok: live version is $SHA"
  exit 0
fi
rollback "new version unhealthy after ${HEALTH_TIMEOUT}s"
