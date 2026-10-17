#!/usr/bin/env bash
# Shared helpers for scripts/qa/*.sh — source, never execute.
# Env overrides: SERIAL (default emulator-5554), ADB (default SDK platform-tools adb).

# shellcheck shell=bash
set -euo pipefail

ADB="${ADB:-~/Library/Android/sdk/platform-tools/adb}"
SERIAL="${SERIAL:-emulator-5554}"
UI_DUMP_REMOTE="/sdcard/bb-ui.xml"

say() { printf '%s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# adb against $SERIAL.
ash() { "$ADB" -s "$SERIAL" shell "$@"; }

# uiautomator dump with retry: animations running or a busy tree make single
# dumps fail. Prints the XML hierarchy to stdout; exits non-zero after 5 tries
# (1s apart).
dump_with_retry() {
  local i xml=""
  for i in 1 2 3 4 5; do
    xml="$(ash uiautomator dump "$UI_DUMP_REMOTE" >/dev/null 2>&1 && ash cat "$UI_DUMP_REMOTE" 2>/dev/null | tr -d '\r')" || xml=""
    if [[ "$xml" == *"<hierarchy"* ]]; then
      printf '%s' "$xml"
      return 0
    fi
    sleep 1
  done
  die "uiautomator dump failed 5x (screen off? animations running? device busy?)"
}

# bounds_of <xml> <resource-id> -> "x1 y1 x2 y2" of the first matching node.
# rc=1 (empty) when the tag is not on screen.
bounds_of() {
  local m
  m="$(printf '%s' "$1" | grep -oE "resource-id=\"$2\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" | head -1 || true)"
  [[ -n "$m" ]] || return 1
  printf '%s' "$m" | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/'
}

# bounds_by_text <xml> <exact text> -> "x1 y1 x2 y2" (nodes without a testTag,
# e.g. dialog buttons).
bounds_by_text() {
  local m
  m="$(printf '%s' "$1" | grep -oE "text=\"$2\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" | head -1 || true)"
  [[ -n "$m" ]] || return 1
  printf '%s' "$m" | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/'
}

# text_of <xml> <resource-id> -> the node's text attribute ("" if empty); rc=1
# when the node is absent.
text_of() {
  local m
  m="$(printf '%s' "$1" | grep -oE "text=\"[^\"]*\" resource-id=\"$2\"" | head -1 || true)"
  [[ -n "$m" ]] || return 1
  printf '%s' "$m" | sed -E 's/^text="(.*)" resource-id=.*/\1/'
}

# center_of x1 y1 x2 y2 -> "x y"
center_of() { echo $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 )); }

# tap_tag <resource-id>: fresh dump, resolve center, tap. rc=1 if tag missing.
tap_tag() {
  local xml b x y
  xml="$(dump_with_retry)"
  b="$(bounds_of "$xml" "$1")" || { printf 'ERROR: tag not found: %s\n' "$1" >&2; return 1; }
  read -r x y <<< "$(center_of $b)"
  ash input tap "$x" "$y"
}

# esc_input_text <string>: escape for `adb shell input text` (device-side sh
# metacharacters; spaces must be %s).
esc_input_text() {
  local s="$1"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  s="${s//\$/\\\$}"
  s="${s//\`/\\\`}"
  s="${s// /%s}"
  printf '%s' "$s"
}

# wait_for_tag <resource-id> [timeout_s=60] [step_s=2]: poll fresh dumps until
# the tag is present; prints the winning XML on stdout. rc=1 on timeout.
wait_for_tag() {
  local tag="$1" timeout="${2:-60}" step="${3:-2}" waited=0 xml
  while :; do
    xml="$(dump_with_retry)" || return 1
    if bounds_of "$xml" "$tag" >/dev/null; then
      printf '%s' "$xml"
      return 0
    fi
    waited=$((waited + step))
    (( waited >= timeout )) && return 1
    sleep "$step"
  done
}
