#!/usr/bin/env bash
# Tap a Compose testTag (resource-id) by resolving it in a fresh uiautomator
# dump — never tap remembered coordinates; the keyboard/dialogs shift layout.
#
# usage: ui-tap-tag.sh [--dump-after] <resource-id>
#   ui-tap-tag.sh dashboard/quick_action/NOTE
#   ui-tap-tag.sh --dump-after login/submit    # print the fresh tree after the tap
# Env: SERIAL (default emulator-5554), ADB.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

usage() {
  sed -n '2,8p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

DUMP_AFTER=0
TAG=""
while (( $# )); do
  case "$1" in
    --dump-after) DUMP_AFTER=1; shift ;;
    -h|--help)    usage 0 ;;
    -*)           usage 1 ;;
    *) 
      [[ -z "$TAG" ]] || usage 1
      TAG="$1"; shift ;;
  esac
done
[[ -n "$TAG" ]] || usage 1

xml="$(dump_with_retry)"
b="$(bounds_of "$xml" "$TAG")" || die "tag not found on current screen: $TAG"
read -r x y <<< "$(center_of $b)"
say "tap $TAG at ($x,$y)"
ash input tap "$x" "$y"

if (( DUMP_AFTER )); then
  dump_with_retry
  printf '\n'
fi
