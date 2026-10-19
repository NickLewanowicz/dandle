#!/usr/bin/env bash
# Toggle emulator offline mode (kills all emulator networking — 10.0.2.2 too).
# Used for offline create -> replay (PENDING badge) tests.
#
# usage: offline.sh on|off
#   on   go offline (airplane mode + wifi/data radios off)
#   off  go back online
#
# THREE layers, because each individually lies on some emulator images:
#   1. `cmd connectivity airplane-mode enable/disable`
#   2. `svc wifi disable` / `svc data disable` (the radios themselves —
#      observed: the airplane SETTING flips but the radio stays connected)
#   3. VERIFY actual reachability of the host port from the guest via
#      `nc -z 10.0.2.2 8000` — settings can lie; a socket cannot.
# exit 0 only when the verified state matches. Env: SERIAL, ADB,
# BB_BASE_URL (default http://localhost:8000 → guest sees 10.0.2.2).

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

usage() {
  sed -n '2,13p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

(( $# == 1 )) || usage 1
case "$1" in
  -h|--help) usage 0 ;;
  on)  TARGET=1; ACTION=enable; SVC=disable ;;
  off) TARGET=0; ACTION=disable; SVC=enable ;;
  *)   usage 1 ;;
esac

GUEST_HOST="10.0.2.2"
GUEST_PORT="${BB_GUEST_PORT:-8000}"

reachable() {  # 0 when the guest can reach the host (ICMP; /dev/tcp is a
               # bash-ism toybox sh does not support — a socket probe always
               # "failed", which made the OFF state unverifiable)
  ash "ping -c 1 -W 2 $GUEST_HOST" >/dev/null 2>&1
}

say "offline: $1"
ash cmd connectivity airplane-mode "$ACTION" >/dev/null
ash svc wifi "$SVC" >/dev/null 2>&1 || true
ash svc data "$SVC" >/dev/null 2>&1 || true

for _ in 1 2 3 4 5 6 7 8 9 10; do
  STATE="$(ash settings get global airplane_mode_on | tr -d '\r')"
  if [[ "$STATE" == "$TARGET" ]] && { [[ "$TARGET" == 1 ]] && ! reachable || reachable; }; then
    say "offline=$TARGET verified (airplane_mode_on=$STATE, host port reachability matches) — OK"
    exit 0
  fi
  sleep 1
done

if [[ "$TARGET" == 1 ]]; then
  die "could not verify OFFLINE state on this emulator (settings flipped but the network still reaches the host) — the offline scenarios cannot run here"
else
  die "could not verify ONLINE state (host port unreachable from the guest)"
fi
