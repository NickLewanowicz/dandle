#!/usr/bin/env bash
# Authenticated curl against the local Baby Buddy server. Pretty-prints the
# JSON response body; HTTP status goes to stderr so stdout stays pipeable.
#
# usage: api.sh METHOD path [json-body]
#   api.sh GET /api/children/
#   api.sh GET /api/children/gregory-hill/
#   api.sh POST /api/notes/ '{"child":1,"note":"hello","time":"2026-10-16T09:00:00-04:00"}'
#   api.sh PATCH /api/notes/42/ '{"note":"fixed"}'
#   api.sh DELETE /api/notes/42/
# Auth: token from $BB_TOKEN_FILE (default /tmp/bb_token — regenerate per
# docs/research/babybuddy-api.md). Base URL: $BB_BASE_URL (default http://localhost:8000).
# Notes: PUT is always 405 on Baby Buddy (use PATCH); /api/profile has no trailing slash.

set -euo pipefail

usage() {
  sed -n '2,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

BB_BASE_URL="${BB_BASE_URL:-http://localhost:8000}"
BB_TOKEN_FILE="${BB_TOKEN_FILE:-/tmp/bb_token}"

(( $# >= 2 )) || usage 1
METHOD="$1"; APATH="$2"; BODY="${3:-}"
case "$1" in -h|--help) usage 0 ;; esac

[[ -f "$BB_TOKEN_FILE" ]] || { printf 'ERROR: token file %s missing (see docs/research/babybuddy-api.md)\n' "$BB_TOKEN_FILE" >&2; exit 1; }
TOKEN="$(tr -d '[:space:]' < "$BB_TOKEN_FILE")"

ARGS=( -sS -X "$METHOD" -H "Authorization: Token $TOKEN" )
[[ -n "$BODY" ]] && ARGS+=( -H 'Content-Type: application/json' -d "$BODY" )

# ONE request: status code appended on the last line, then split apart.
OUT="$(curl "${ARGS[@]}" -w '\n%{http_code}' "$BB_BASE_URL$APATH")"
CODE="${OUT##*$'\n'}"
RESP="${OUT%$'\n'*}"

printf 'HTTP %s\n' "$CODE" >&2
if printf '%s' "$RESP" | python3 -m json.tool >/dev/null 2>&1; then
  printf '%s' "$RESP" | python3 -m json.tool
else
  [[ -n "$RESP" ]] && printf '%s\n' "$RESP"
fi
