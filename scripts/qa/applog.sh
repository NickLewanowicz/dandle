#!/usr/bin/env bash
# Tail (or dump) the device log filtered to the app's debug logging.
# Never test blind: keep this running during any emulator test session.
#
# Tag convention (debug builds log user actions + all network traffic):
#   BB/LOGIN  login flow steps       BB/SYNC   outbox/sync engine
#   BB/TIMER  timer start/stop/save  BB/API    request/response summaries
#   BB/UI     navigation, dialogs    + OkHttp  raw request/response lines
# (transitional tags in early builds: BBLogin, BabyBuddyHttp — also matched)
#
# usage: applog.sh [-d]
#   -d   dump the current buffer once and exit (no streaming)
# Without -d, streams until Ctrl-C.
# Env: SERIAL (default emulator-5554), ADB.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

usage() {
  sed -n '2,13p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

DUMP=0
while (( $# )); do
  case "$1" in
    -d) DUMP=1; shift ;;
    -h|--help) usage 0 ;;
    *) usage 1 ;;
  esac
done

# BB/* convention (slash, dot or underscore separators) + transitional tags + OkHttp.
FILTER='BB[/._]|BabyBuddy|okhttp|OkHttp'

if (( DUMP )); then
  buf="$(ash logcat -d -v time)"
  printf '%s\n' "$buf" | grep -aE "$FILTER" || true
else
  say "streaming app log (BB/*, OkHttp) — Ctrl-C to stop"
  "$ADB" -s "$SERIAL" logcat -v time 2>/dev/null | grep -aE --line-buffered "$FILTER" || true
fi
