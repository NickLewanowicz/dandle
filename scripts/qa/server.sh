#!/usr/bin/env bash
# Stop / start the Baby Buddy docker server, or wait until it answers HTTP.
# Used for offline create -> replay tests (server-down flavor).
#
# usage: server.sh stop|start|wait
#   stop   docker stop babybuddy
#   start  docker start babybuddy
#   wait   poll until the server answers any HTTP status (60s timeout)
# Env: CONTAINER (default babybuddy), BB_BASE_URL (default http://localhost:8000).

set -euo pipefail

usage() {
  sed -n '2,9p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

CONTAINER="${CONTAINER:-babybuddy}"
BB_BASE_URL="${BB_BASE_URL:-http://localhost:8000}"

(( $# == 1 )) || usage 1
CMD="$1"
case "$CMD" in -h|--help) usage 0 ;; esac

wait_up() {
  local i code
  for i in $(seq 1 60); do
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "$BB_BASE_URL/" || true)"
    if [[ "$code" != "000" && -n "$code" ]]; then
      printf 'server is up (HTTP %s)\n' "$code"
      return 0
    fi
    sleep 1
  done
  printf 'ERROR: server at %s did not answer within 60s\n' "$BB_BASE_URL" >&2
  return 1
}

case "$CMD" in
  stop)
    printf 'stopping container %s\n' "$CONTAINER"
    docker stop "$CONTAINER" >/dev/null
    ;;
  start)
    printf 'starting container %s\n' "$CONTAINER"
    docker start "$CONTAINER" >/dev/null
    wait_up
    ;;
  wait)
    wait_up
    ;;
  *) usage 1 ;;
esac
