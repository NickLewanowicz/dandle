#!/usr/bin/env bash
# Show Baby Buddy HTTP request lines — verify server effects, not just UI.
#
# Primary source: nginx access log inside the container
# (/config/log/nginx/access.log on the LinuxServer.io image — gunicorn access
# logging is OFF in that image, so `docker logs` alone shows no request lines).
# Fallback: container stdout filtered to request lines.
#
# usage: server-log.sh [-f] [-n N]
#   -f    follow (Ctrl-C to stop)
#   -n N  last N request lines (default 50)
# Env: CONTAINER (default babybuddy), BB_LOG_FILE (default /config/log/nginx/access.log).

set -euo pipefail

usage() {
  sed -n '2,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

CONTAINER="${CONTAINER:-babybuddy}"
BB_LOG_FILE="${BB_LOG_FILE:-/config/log/nginx/access.log}"
FOLLOW=0
N=50

while (( $# )); do
  case "$1" in
    -f) FOLLOW=1; shift ;;
    -n) N="$2"; shift 2 ;;
    -h|--help) usage 0 ;;
    *) usage 1 ;;
  esac
done

docker inspect -f '{{.State.Running}}' "$CONTAINER" >/dev/null 2>&1 \
  || { printf 'ERROR: docker container "%s" not found/running\n' "$CONTAINER" >&2; exit 1; }

has_log_file() {
  docker exec "$CONTAINER" test -s "$BB_LOG_FILE" >/dev/null 2>&1
}

if has_log_file; then
  if (( FOLLOW )); then
    docker exec "$CONTAINER" tail -n "$N" -F "$BB_LOG_FILE" &
    TAIL_PID=$!
    trap 'kill "$TAIL_PID" 2>/dev/null || true' EXIT INT
    wait "$TAIL_PID" 2>/dev/null || true
    trap - EXIT INT
  else
    docker exec "$CONTAINER" tail -n "$N" "$BB_LOG_FILE"
  fi
else
  # Fallback: stdout request lines (works when access logging reaches docker logs).
  # gunicorn access lines: [date] [pid] [INFO] "GET /api/ HTTP/1.1" 200 ...
  FILTER='"(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS) [^ ]+ HTTP/[0-9.]+".*'
  if (( FOLLOW )); then
    docker logs -f --tail "$(( N * 5 ))" "$CONTAINER" 2>&1 | grep -aE --line-buffered "$FILTER" || true
  else
    docker logs --tail "$(( N * 5 ))" "$CONTAINER" 2>&1 | grep -aE "$FILTER" | tail -n "$N" || true
  fi
fi
