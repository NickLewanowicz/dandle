#!/usr/bin/env bash
# Print the text attribute of the node with the given resource-id (Compose
# testTag). Empty text prints an empty line (node present). Missing node: exit 1.
#
# usage: ui-text.sh <resource-id>
#   ui-text.sh login/error_banner
#   ui-text.sh dashboard/panel/note_text
# Env: SERIAL (default emulator-5554), ADB.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

usage() {
  sed -n '2,7p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

(( $# == 1 )) || usage 1
case "$1" in -h|--help) usage 0 ;; esac

xml="$(dump_with_retry)"
text="$(text_of "$xml" "$1")" || die "tag not found on current screen: $1"
printf '%s\n' "$text"
