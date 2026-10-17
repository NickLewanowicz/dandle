#!/usr/bin/env bash
# Toggle emulator airplane mode (kills all emulator networking — 10.0.2.2 too).
# Used for offline create -> replay (PENDING badge) tests.
#
# usage: offline.sh on|off
#   on   enable airplane mode (offline)
#   off  disable airplane mode (back online)
# Verifies settings global airplane_mode_on reaches the target state before
# exiting 0. Env: SERIAL (default emulator-5554), ADB.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

usage() {
  sed -n '2,10p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

(( $# == 1 )) || usage 1
case "$1" in
  -h|--help) usage 0 ;;
  on)  TARGET=1; ACTION=enable ;;
  off) TARGET=0; ACTION=disable ;;
  *)   usage 1 ;;
esac

say "airplane mode: $1"
ash cmd connectivity airplane-mode "$ACTION" >/dev/null

for _ in 1 2 3 4 5 6 7 8 9 10; do
  STATE="$(ash settings get global airplane_mode_on | tr -d '\r')"
  [[ "$STATE" == "$TARGET" ]] && { say "airplane_mode_on=$STATE — OK"; exit 0; }
  sleep 1
done

die "airplane mode did not reach state $TARGET (got: ${STATE:-?})"
