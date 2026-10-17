#!/usr/bin/env bash
# Screenshot the device to docs/screenshots/<name>.png — screenshots are
# documentation, not decoration: capture one at every meaningful state.
#
# usage: shot.sh <name>
#   shot.sh qa/login/01-login-screen     -> docs/screenshots/qa/login/01-login-screen.png
#   shot.sh existing-app/dashboard       (.png suffix optional)
# Works from any cwd (resolves the repo root from this script's location).
# Env: SERIAL (default emulator-5554), ADB.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

usage() {
  sed -n '2,9p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

(( $# == 1 )) || usage 1
case "$1" in -h|--help) usage 0 ;; esac

NAME="$1"
NAME="${NAME%.png}"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUT="$REPO_ROOT/docs/screenshots/$NAME.png"

mkdir -p "$(dirname "$OUT")"
# exec-out is binary-safe (plain `adb shell screencap` corrupts PNGs with CRLF).
"$ADB" -s "$SERIAL" exec-out screencap -p > "$OUT"

[[ -s "$OUT" ]] || die "screenshot came back empty: $OUT"
printf 'saved %s (%s bytes)\n' "$OUT" "$(wc -c < "$OUT" | tr -d ' ')"
