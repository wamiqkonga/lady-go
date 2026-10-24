#!/usr/bin/env bash
# Tears down everything scripts/dev-up.sh started.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_DIR="$ROOT/.dev-stack"

if [[ ! -d "$STATE_DIR" ]]; then
  echo "No dev stack state found ($STATE_DIR) - nothing to do."
  exit 0
fi

if [[ -f "$STATE_DIR/admin-web.pid" ]]; then
  PID=$(cat "$STATE_DIR/admin-web.pid")
  kill "$PID" 2>/dev/null && echo "Stopped admin web (pid $PID)."
fi

if [[ -f "$STATE_DIR/backend.pid" ]]; then
  PID=$(cat "$STATE_DIR/backend.pid")
  kill "$PID" 2>/dev/null && echo "Stopped backend (pid $PID)."
fi
pkill -f "spring-boot:run" 2>/dev/null || true

if [[ -f "$STATE_DIR/state.env" ]]; then
  # shellcheck disable=SC1091
  source "$STATE_DIR/state.env"
  if [[ -n "${PG_CONTAINER:-}" ]]; then
    docker rm -f "$PG_CONTAINER" >/dev/null 2>&1 && echo "Removed Postgres container $PG_CONTAINER."
  fi
fi

rm -rf "$STATE_DIR"
echo "Dev stack torn down."
