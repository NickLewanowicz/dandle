#!/usr/bin/env bash
# Fresh-install login, fully scripted — never hand-drive this flow again.
#
# Force-stop -> pm clear -> disable animations -> launch -> type login fields
# BY TESTTAG (fresh dump before every tap: the keyboard shifts the layout, so
# coordinates are never reused) -> handle the http:// cleartext warning
# ("Continue") -> exit 0 only when the `dashboard` tag is visible. If the
# `login/error_banner` tag appears, exits 2 with the banner text.
#
# usage: fresh-install-login.sh [--package PKG] [--url URL] [--user U] [--pass P] [--keep-data]
#   --package PKG   app package (default app.dandle.debug)
#   --url URL       server URL to type (default http://10.0.2.2:8000; triggers the cleartext dialog)
#   --user U        username (default admin)      --pass P password (default admin)
#   --keep-data     skip `pm clear` (fields may be pre-filled; script clears them)
# Env: SERIAL (default emulator-5554), ADB.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

usage() {
  sed -n '2,14p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

PKG="app.dandle.debug"
URL="http://10.0.2.2:8000"
APP_USER="admin"
APP_PASS="admin"
KEEP_DATA=0

while (( $# )); do
  case "$1" in
    --package)   PKG="$2"; shift 2 ;;
    --url)       URL="$2"; shift 2 ;;
    --user)      APP_USER="$2"; shift 2 ;;
    --pass)      APP_PASS="$2"; shift 2 ;;
    --keep-data) KEEP_DATA=1; shift ;;
    -h|--help)   usage 0 ;;
    *)           usage 1 ;;
  esac
done

# type_field <tag> <value>: dump FRESH, tap the field's center (layout may have
# shifted under the keyboard from the previous field), clear any pre-filled
# text, then type. Callers must re-dump for the next field — never cache bounds.
type_field() {
  local tag="$1" value="$2" xml b x y dels
  xml="$(dump_with_retry)"
  b="$(bounds_of "$xml" "$tag")" || die "login field not on screen: $tag"
  read -r x y <<< "$(center_of $b)"
  say "tap $tag at ($x,$y)"
  ash input tap "$x" "$y"
  ash input keyevent KEYCODE_MOVE_END
  dels="$(printf 'KEYCODE_DEL %.0s' {1..40})"   # one round-trip; no-op if empty
  ash input keyevent $dels
  ash input text "$(esc_input_text "$value")"
  say "typed $tag"
}

say "==> target $PKG on $SERIAL"
say "==> force-stop"
ash am force-stop "$PKG"

if (( ! KEEP_DATA )); then
  say "==> pm clear (fresh install state)"
  ash pm clear "$PKG" >/dev/null
else
  say "==> keeping app data (--keep-data)"
fi

say "==> disable animations (uiautomator-friendly)"
ash settings put global window_animation_scale 0
ash settings put global transition_animation_scale 0
ash settings put global animator_duration_scale 0

say "==> launch launcher activity"
COMP="$(ash cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$PKG" 2>/dev/null | tr -d '\r' | grep -v '^$' | tail -1 || true)"
if [[ "$COMP" == */* ]]; then
  say "    starting $COMP"
  ash am start -n "$COMP" >/dev/null
else
  say "    resolve-activity empty; falling back to monkey"
  ash monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
fi

say "==> wait for login screen (tag: login/server_url)"
wait_for_tag "login/server_url" 60 2 >/dev/null || die "login screen never appeared (60s)"

say "==> typing credentials (fresh dump before every tap)"
type_field "login/server_url" "$URL"
type_field "login/username" "$APP_USER"
type_field "login/password" "$APP_PASS"

say "==> tap login/submit"
tap_tag "login/submit" || die "login/submit not on screen after typing"

say "==> waiting for dashboard / cleartext dialog / error banner"
deadline=$(( SECONDS + 60 ))
dialog_done=0
while (( SECONDS < deadline )); do
  xml="$(dump_with_retry)"

  if bounds_of "$xml" "dashboard" >/dev/null; then
    say "==> dashboard visible — LOGIN OK"
    exit 0
  fi

  if (( ! dialog_done )); then
    if b="$(bounds_by_text "$xml" "Continue")"; then
      read -r x y <<< "$(center_of $b)"
      say "==> cleartext-warning dialog: tapping Continue at ($x,$y)"
      ash input tap "$x" "$y"
      dialog_done=1
    fi
  fi

  if banner="$(text_of "$xml" "login/error_banner")"; then
    say "==> login/error_banner: \"$banner\""
    printf 'ERROR: login rejected — error banner: %s\n' "$banner" >&2
    exit 2
  fi

  sleep 2
done

die "timeout (60s): no dashboard, no cleartext dialog, no error banner — check applog.sh"
