#!/usr/bin/env bash
# Automated flow capture: drives the app through EVERY documented user-flow
# state and captures numbered PNGs into docs/screenshots/new-app/flows/.
# This PNG set IS the visual source of truth for docs — re-running overwrites
# it in place (never hand-capture these).
#
# usage: capture-flows.sh [--assert]
#   (no flags)  capture all flow screenshots (groups 01-07)
#   --assert    same drive, plus regression canaries (see below); prints
#               PASS/FAIL lines, exits non-zero on any FAIL
#
# Regression canaries (--assert) — pin Jordan's #1 beta bug (saves must appear
# instantly, edits must update rows, no restart needed):
#   A1 save-visibility: after saving a diaper via the quick-action panel, the
#      uiautomator dump must contain a "Today" timeline row showing our unique
#      green-diaper row (swatch "Green") within 12 s — WITHOUT restart or refresh.
#   A2 edit-update: after moving START one hour earlier through the native
#      time picker (owner rework U6 — no steppers) + Save, the timeline row
#      must show the new HH:mm within 10 s.
# T1 regression history (persona round 1, triage FINAL): the timeline used to
# stop re-rendering from Room (rows appeared only after restart/refresh), and
# BOTH canaries FAILED — that failure was the regression evidence. FIXED
# (ba7e009): writes nudge a timeline re-query, so rows render immediately;
# the canary windows allow 12 s (A1) / 10 s (A2) and treat a FAILED dump
# (UiAutomation transiently busy after a save) as infra, not app evidence.
# The script uses app restarts elsewhere purely for CAPTURE determinism
# (never inside a canary window).
#
# Preconditions: emulator booted, app INSTALLED AND LOGGED IN (the script
# detects login-vs-dashboard and handles both — it signs out via the app to
# reach the login states, then signs back in), both widgets on the home screen,
# server up (`scripts/qa/server.sh wait`). Milo Rivera + Zoe Rivera must exist.
#
# Guarantees:
#   - airplane mode / stopped server are ALWAYS restored (trap EXIT), even on
#     failure.
#   - End state: app on dashboard, active child = Milo, offline off, server
#     running, no running timers (local strip discarded AND leftover
#     "<Type>-BBapp:<n>" server timers purged — they would resurrect on next
#     launch otherwise).
#   - Test events created by this script are deleted afterwards via
#     api.sh DELETE on ids THIS script created (never seeded ones): entries are
#     tagged with exact-match markers (amount 149/73 mL, notes "QA capture…"/
#     "QA-capture-A/B").
#
# Env: SERIAL (default emulator-5554), ADB, BB_BASE_URL/BB_TOKEN_FILE for cleanup.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

# Override lib.sh's dump_with_retry for THIS script only: after rapid
# force-stop + dump cycles (or dumping during the app's profile-install cold
# start) the device-side UiAutomation connection can be wedged
# ("UiAutomationService already registered") and every dump fails for a
# while — so retry with PROGRESSIVE backoff (~45 s total) and RETURN 1
# instead of dying, letting callers decide (the trap restores state).
dump_with_retry() {
  local i xml="" delay
  for i in 1 2 3 4 5 6 7 8 9 10; do
    xml="$(ash uiautomator dump "$UI_DUMP_REMOTE" >/dev/null 2>&1 && ash cat "$UI_DUMP_REMOTE" 2>/dev/null | tr -d '\r')" || xml=""
    if [[ "$xml" == *"<hierarchy"* ]]; then
      printf '%s' "$xml"
      return 0
    fi
    delay=$(( (i < 4) ? i + 1 : 6 ))
    sleep "$delay"
  done
  warn "uiautomator dump failed 10x (screen off? animations? wedged UiAutomation?)"
  return 1
}

usage() {
  sed -n '2,34p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

ASSERT=0
while (( $# )); do
  case "$1" in
    --assert)  ASSERT=1; shift ;;
    -h|--help) usage 0 ;;
    *)         usage 1 ;;
  esac
done

PKG="net.babybuddy.next.debug"
OUTREL="new-app/flows"                    # relative to docs/screenshots/
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SHOTDIR="$REPO_ROOT/docs/screenshots/$OUTREL"
mkdir -p "$SHOTDIR"

FAILURES=0
OFFLINE_ON=0
SERVER_STOPPED=0
CLEANED=0
LAUNCHER_COMP=""
SCREEN_W=1080
SCREEN_H=2340

step()  { printf '\n==> %s\n' "$*"; }
warn()  { printf 'WARN: %s\n' "$*" >&2; }
pass()  { printf 'PASS: %s\n' "$*"; }
failq() { printf 'FAIL: %s\n' "$*"; FAILURES=$((FAILURES + 1)); }

# An assertion: PASS/FAIL line in --assert mode, INFO line otherwise.
# usage: assert_chk <0|1_condition_result> <description>
assert_chk() {
  if (( $1 )); then
    (( ASSERT )) && pass "$2" || printf 'INFO: ok — %s\n' "$2"
  else
    if (( ASSERT )); then failq "$2"; else warn "canary would FAIL (assert-only): $2"; fi
  fi
}

# ---------------------------------------------------------------- helpers ---

shot() {  # shot <name-without-extension> — never fails the run
  local name="$1"
  if "$SCRIPT_DIR/shot.sh" "$OUTREL/$name" >/dev/null 2>&1; then
    printf 'shot %s/%s.png\n' "$OUTREL" "$name"
  else
    warn "screenshot failed: $name"
  fi
}

# bounds_by_text_all <xml> <exact text> -> one "x1 y1 x2 y2" per line, ALL matches.
bounds_by_text_all() {
  printf '%s' "$1" \
    | grep -oE "text=\"$2\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" \
    | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/' \
    || true
}

# tap_text <xml> <exact text> [nth=1]: tap the nth (1-based) exact-text node.
tap_text() {
  local xml="$1" text="$2" nth="${3:-1}" x1 y1 x2 y2 x y n=0
  while read -r x1 y1 x2 y2; do
    n=$((n + 1))
    if (( n == nth )); then
      read -r x y <<< "$(center_of "$x1" "$y1" "$x2" "$y2")"
      ash input tap "$x" "$y"
      return 0
    fi
  done <<< "$(bounds_by_text_all "$xml" "$text")"
  printf 'ERROR: text not on screen: %s\n' "$text" >&2
  return 1
}

# tap_row_text <exact text>: fresh dump, tap the FIRST node with this text
# (timeline rows are newest-first, so QA's own newest entry matches first).
tap_row_text() {
  local xml b x y
  xml="$(dump_with_retry)" || return 1
  b="$(bounds_by_text "$xml" "$1")" || { printf 'ERROR: row text not found: %s\n' "$1" >&2; return 1; }
  read -r x y <<< "$(center_of $b)"
  ash input tap "$x" "$y"
}

# bounds_by_text_like <xml> <substring>: bounds of the first text node whose
# text CONTAINS the substring (used as an ERE fragment — pass literals). Needed
# because the app embeds layout spacing INSIDE text nodes: the diaper row's
# swatch renders as " Black" (TimelineSection prepends the gap after the color
# dot), so an exact `text="Black"` never matches the dump.
bounds_by_text_like() {
  local m
  m="$(printf '%s' "$1" | grep -oE "text=\"[^\"]*$2[^\"]*\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" | head -1 || true)"
  [[ -n "$m" ]] || return 1
  printf '%s' "$m" | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/'
}

# tap_row_text_like <substring>: like tap_row_text but substring-matching.
tap_row_text_like() {
  local xml b x y
  xml="$(dump_with_retry)" || return 1
  b="$(bounds_by_text_like "$xml" "$1")" || { printf 'ERROR: row text (contains) not found: %s\n' "$1" >&2; return 1; }
  read -r x y <<< "$(center_of $b)"
  ash input tap "$x" "$y"
}

# tap_row_text_nth <text> <n>: tap the Nth (1-based) exact-text match — needed
# inside Compose dialog windows (edit sheet) whose test tags never reach the
# uiautomator dump (QA finding #8), e.g. the END row's "+1m" (the 2nd "+1m").
tap_row_text_nth() {
  local xml b x y n="$2" i=0
  xml="$(dump_with_retry)" || return 1
  b="$(printf '%s' "$xml" | grep -o "text=\"$1\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\\[[0-9]*,[0-9]*\\]\"" | sed -n "${n}p" | grep -o 'bounds="[^"]*"')"
  [[ -z "$b" ]] && { printf 'ERROR: row text (nth %s) not found: %s\n' "$n" "$1" >&2; return 1; }
  read -r x y <<< "$(center_of $b)"
  ash input tap "$x" "$y"
}

# verify_typed <got> <want>: equal — or the field is a password field reporting
# a dot-masked string of the same length.
verify_typed() {
  [[ "$1" == "$2" ]] && return 0
  local stripped="${1//•/}"
  [[ -z "$stripped" && ${#1} -eq ${#2} && -n "$2" ]]
}

# type_into <tag> <value>: fresh dump, tap field, select-all-clear, type — then
# VERIFY the value landed (the IME occasionally eats input right after an ANR
# recovery or keyboard show) and retry once from a clean keyboard state.
type_into() {
  local tag="$1" value="$2" attempt b x y dels got
  dels="$(printf 'KEYCODE_DEL %.0s' {1..40})"
  for attempt in 1 2; do
    local xml
    xml="$(dump_with_retry)" || return 1
    b="$(bounds_of "$xml" "$tag")" || { printf 'ERROR: field not on screen: %s\n' "$tag" >&2; return 1; }
    read -r x y <<< "$(center_of $b)"
    ash input tap "$x" "$y"
    sleep 1
    ash input keyevent KEYCODE_MOVE_END
    ash input keyevent $dels
    # Type ONE character at a time: bulk `input text` drops/swaps characters
    # when the field's input connection lags ("offline" -> "ofline").
    local j ch
    for (( j=0; j<${#value}; j++ )); do
      ch="${value:j:1}"
      [[ "$ch" == " " ]] && ch="%s"
      # A bare "-" is parsed by `input text` as a FLAG and dropped: escape it
      # ("QA-capture-A" otherwise lands as "QA", breaking every text match).
      [[ "$ch" == "-" ]] && ch="\\-"
      ash input text "$ch"
      sleep 0.15
    done
    sleep 1
    got="$(text_of "$(dump_with_retry)" "$tag" 2>/dev/null || true)"
    got="${got%$'\n'}"; got="${got%$'\r'}"   # a stray ENTER newline must not fail verify
    if verify_typed "$got" "$value"; then
      return 0
    fi
    printf 'WARN: %s = "%s" (wanted "%s") — retrying\n' "$tag" "$got" "$value" >&2
    hide_kb
  done
  warn "typing into $tag did not verify"
  return 1
}

hide_kb() {  # dismiss the IME with ESC (111) and VERIFY it is gone.
             # ESC hides the IME with no navigation side effect (BACK dismisses
             # sheets; ENTER types a newline into multiline fields — both
             # observed). The shown-flag can lie (Gboard zombie state), so the
             # loop re-checks and re-fires ESC, then settles before returning.
  local i=0
  while :; do
    if ash dumpsys input_method 2>/dev/null | tr -d '\r' | grep -q 'mInputShown=true'; then
      ash input keyevent 111
      sleep 2
      i=$((i + 1))
      (( i >= 4 )) && { warn "keyboard would not dismiss"; return 1; }
    else
      sleep 1
      if ash dumpsys input_method 2>/dev/null | tr -d '\r' | grep -q 'mInputShown=true'; then
        continue   # it popped up after all - dismiss on the next pass
      fi
      sleep 1.5   # let the window un-resize before anything reads coordinates
      return 0
    fi
  done
}

# expand_sheet: the quick edit sheet opens HALF-expanded — its time rows,
# steppers, and Save sit below the fold. One upward drag fully expands it.
expand_sheet() {
  ash input swipe "$(( SCREEN_W / 2 ))" "$(( SCREEN_H * 80 / 100 ))" "$(( SCREEN_W / 2 ))" "$(( SCREEN_H * 30 / 100 ))" 300
  sleep 2
}

# handle_anr: if the system "isn't responding" dialog is up (sign-out can ANR
# the app while it clears its DB on the main thread), tap "Wait" so the app can
# finish instead of killing the process.
handle_anr() {
  local xml
  if xml="$(dump_with_retry 2>/dev/null)"; then
    if printf '%s' "$xml" | grep -q "isn't responding"; then
      warn "ANR dialog detected — tapping Wait"
      tap_text "$xml" "Wait" || true
      sleep 3
    fi
  fi
}

# ensure_visible <tag>: swipe the scrollable up until the tag is composed AND
# well clear of the gesture-nav-bar inset — a timer card in the strip pushes
# the panel bottom (Save) under the nav bar, where Compose still reports
# layout bounds but taps land on nothing. Dump failures (wedged UiAutomation)
# are waited out WITHOUT swiping; only genuinely-too-low bounds scroll.
ensure_visible() {
  # Small upward drags (12% of the screen) that start ABOVE any keyboard
  # (the IME occupies the bottom ~43%): the running-timer card is the
  # timeline's first list item (U2) and content clipped out of the list's
  # viewport is pruned from the uiautomator tree — a big 40% fling scrolled
  # the card clean out of view; a drag that starts on the keyboard scrolls
  # nothing. Also tolerates a visible keyboard: a node whose bottom edge sits
  # above it is tappable without dismissing the IME at all.
  local tag="$1" i b b2 y2 xml limit
  for i in 1 2 3 4 5 6 7 8 9 10; do
    if xml="$(dump_with_retry 2>/dev/null)"; then
      if b="$(bounds_of "$xml" "$tag")"; then
        y2="$(printf '%s' "$b" | awk '{print $4}')"
        limit=$(( SCREEN_H - 40 ))
        if ash dumpsys input_method 2>/dev/null | tr -d '\r' | grep -q 'mInputShown=true'; then
          limit=$(( SCREEN_H * 55 / 100 ))
        fi
        (( y2 <= limit )) && return 0
      fi
      ash input swipe "$(( SCREEN_W / 2 ))" "$(( SCREEN_H * 50 / 100 ))" "$(( SCREEN_W / 2 ))" "$(( SCREEN_H * 38 / 100 ))" 300
    fi
    sleep 2
  done
  warn "could not bring $tag on screen"
  return 1
}

# sheet_time_value <tag> <xml>: the StepperTimeRow's testTag sits on the
# container — the actual "Today HH:mm" value is a CHILD text node. Return the
# first HH:mm text whose center lies inside the container's bounds.
sheet_time_value() {
  local tag="$2" b x1 y1 x2 y2 m tx1 ty1 tx2 ty2
  b="$(bounds_of "$2" "$tag")" || return 1
  read -r x1 y1 x2 y2 <<< "$b"
  while IFS= read -r m; do
    tx1="$(printf '%s' "$m" | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1/')"
    ty1="$(printf '%s' "$m" | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\2/')"
    tx2="$(printf '%s' "$m" | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\3/')"
    ty2="$(printf '%s' "$m" | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\4/')"
    if (( (tx1 + tx2) / 2 >= x1 && (tx1 + tx2) / 2 <= x2 && (ty1 + ty2) / 2 >= y1 && (ty1 + ty2) / 2 <= y2 )); then
      printf '%s\n' "$m" | sed -E 's/^text="([^"]*)".*/\1/'
      return 0
    fi
  done < <(printf '%s' "$2" | grep -oE 'text="[^"]*[0-9]{1,2}:[0-9]{2}"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"')
  return 1
}

# Visible-safe variants for panel/sheet content that may sit below the fold.
# vtag: reveal, then tap the already-resolved bounds — NOT a second dump. A
# second uiautomator pass can re-show the IME for a focused text field and the
# window re-shrinks mid-flight, so the tap misses (observed on device).
vtag() {  # vtag <tag> [allow_down]: sheets pass 0 (a down-drag dismisses them)
  local b x1 y1 x2 y2
  b="$(reveal_tag "$1" "${2:-1}")" || return 1
  read -r x1 y1 x2 y2 <<< "$b"
  ash input tap "$(( (x1 + x2) / 2 ))" "$(( (y1 + y2) / 2 ))"
}
vtype() { ensure_visible "$1" && type_into "$1" "$2"; }

# save_panel <TYPE> [no-accept-dup]: save the open quick-action panel.
# Timer types: the STRIP Save is used first — it is always visible at the top
# of the screen (the panel's own Save is clipped when a strip card squeezes
# the panel) and reads the SAME panel draft (amount/notes typed in the panel
# are included). Instant types (CHANGE/NOTE): the panel Save. The duplicate
# guard is accepted with its default Add action unless no-accept-dup is given
# (used by the duplicate-guard capture step, which must SHOOT the dialog).
save_panel() {
  local type="$1" accept="${2:-accept}" xml i
  # up to 3 attempts: hide the IME, tap the save, verify the panel actually
  # closed (a tap under the keyboard window is silently eaten otherwise)
  for i in 1 2 3; do
    hide_kb || true
    # Reveal the save once, then tap the RESOLVED coordinates (see reveal_tag).
    # The card is a list item (U2): its icon-only save may sit below the fold or
    # below the keyboard. Sheets get up-drags only (a down-drag dismisses them).
    local sb stag allow
    if [[ "$type" == "CHANGE" || "$type" == "NOTE" ]]; then
      stag="dashboard/panel/save"; allow=0
    else
      stag="dashboard/timer/$type/save"; allow=1
    fi
    sb="$(reveal_tag "$stag" "$allow")" || { warn "no save path for $type ($stag)"; return 1; }
    ash input tap $(( $(echo "$sb" | awk '{print int(($1+$3)/2)}') )) $(( $(echo "$sb" | awk '{print int(($2+$4)/2)}') ))
    sleep 3
    xml="$(dump_with_retry 2>/dev/null)" || return 0
    if ! bounds_of "$xml" "dashboard/panel/$type" >/dev/null; then
      break   # panel closed - saved
    fi
    warn "save tap did not close the $type panel (attempt $i)"
  done
  if [[ "$accept" != "no-accept-dup" ]]; then
    if xml="$(dump_with_retry 2>/dev/null)"; then
      if bounds_of "$xml" "dashboard/duplicate_dialog" >/dev/null; then
        printf '    duplicate guard shown - accepting (Add)\n'
        tap_tag "dashboard/duplicate_dialog/add" || true
      fi
    fi
  fi
}

scroll_top() {  # scroll the dashboard back up (3 gentle down-swipes)
  local i
  for i in 1 2 3; do
    ash input swipe "$(( SCREEN_W / 2 ))" "$(( SCREEN_H * 40 / 100 ))" "$(( SCREEN_W / 2 ))" "$(( SCREEN_H * 75 / 100 ))" 200
    sleep 1
  done
}

refresh_timeline() {  # pull-to-refresh gesture (best-effort; T1 may ignore it)
  ash input swipe "$(( SCREEN_W / 2 ))" "$(( SCREEN_H * 30 / 100 ))" "$(( SCREEN_W / 2 ))" "$(( SCREEN_H * 58 / 100 ))" 300
  sleep 3
}

wait_gone_tag() {  # wait_gone_tag <tag> <timeout_s>: 0 when absent, 1 on timeout
  local tag="$1" timeout="$2" waited=0 xml
  while :; do
    xml="$(dump_with_retry)" || return 0
    bounds_of "$xml" "$tag" >/dev/null || return 0
    waited=$((waited + 2))
    (( waited >= timeout )) && return 1
    sleep 2
  done
}

wait_text() {  # wait_text <exact text> <timeout_s>
  local text="$1" timeout="$2" waited=0 xml
  while :; do
    if xml="$(dump_with_retry 2>/dev/null)"; then
      bounds_by_text "$xml" "$text" >/dev/null && return 0
    fi
    waited=$((waited + 2))
    (( waited >= timeout )) && return 1
    sleep 2
  done
}

dev_now_hm() { ash date +%H:%M 2>/dev/null | tr -d '\r'; }  # (debugging aid)

press_home() {
  ash input keyevent KEYCODE_HOME
  sleep 2
  wait_launcher 5 || {
    # rare: HOME key ignored — force the launcher intent
    ash am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1 || true
    sleep 2
    wait_launcher 5 || true
  }
}

wait_launcher() {  # poll current focus until the launcher is frontmost
  local timeout="${1:-10}" waited=0 focus
  while :; do
    focus="$(ash dumpsys window 2>/dev/null | tr -d '\r' | grep -m1 'mCurrentFocus' || true)"
    if printf '%s' "$focus" | grep -qiE 'launcher'; then return 0; fi
    waited=$((waited + 1))
    (( waited >= timeout )) && { warn "launcher focus not detected ($focus)"; return 1; }
    sleep 1
  done
}

open_app() {  # bring the app to the foreground; ANR-aware, with a force-stop
              # + relaunch as the last resort. 0 when the dashboard tag shows.
  local attempt
  for attempt in 1 2 3; do
    handle_anr
    [[ -n "$LAUNCHER_COMP" ]] || return 1
    ash am start -n "$LAUNCHER_COMP" >/dev/null 2>&1 || true
    if wait_for_tag "dashboard" 15 2 >/dev/null; then return 0; fi
    ash input keyevent 4 2>/dev/null || true
    sleep 2
  done
  # last resort: a fresh process (clears stuck dialogs, restored timers, etc.)
  ash am force-stop "$PKG"
  sleep 3
  ash am start -n "$LAUNCHER_COMP" >/dev/null 2>&1 || true
  if wait_for_tag "dashboard" 20 2 >/dev/null; then return 0; fi
  warn "dashboard not visible after open_app"
  return 1
}

restart_app() {  # force-stop + relaunch. The ONE reliable way to re-render the
                 # timeline from Room while the T1 regression is unfixed.
  ash am force-stop "$PKG"
  sleep 3
  ash am start -n "$LAUNCHER_COMP" >/dev/null 2>&1 || true
  # A persisted-open create draft restores as a SHEET at launch (process-death
  # restore is a feature) — its window hides every dashboard tag. Close it.
  local ri
  for ri in 1 2 3 4 5; do
    if wait_for_tag "dashboard" 6 2 >/dev/null; then break; fi
    if wait_for_tag "dashboard/panel/close" 3 2 >/dev/null; then
      warn "restored create sheet after restart — closing it"
      tap_tag "dashboard/panel/close" || true
      sleep 2
    fi
  done
  wait_for_tag "dashboard" 10 2 >/dev/null || { warn "dashboard not visible after restart"; return 1; }
  sleep 2
}

longpress_at() { ash input swipe "$1" "$2" "$1" "$2" 1000; sleep 1; }

front_focus() {  # print the current-focus window line (best-effort)
  ash dumpsys window 2>/dev/null | tr -d '\r' | grep -m1 'mCurrentFocus' || true
}

wait_focus_pkg() {  # wait until the app package holds the window focus
  local timeout="${1:-10}" waited=0
  while :; do
    if printf '%s' "$(front_focus)" | grep -q "$PKG"; then return 0; fi
    waited=$((waited + 1))
    (( waited >= timeout )) && return 1
    sleep 1
  done
}

# Stop a running <TYPE> timer via the STRIP's own Discard button (no dialog,
# minimal dumps — ticking counters make dumps flaky). Falls back to the
# card -> stop-dialog path. Best-effort, never fatal.
discard_timer() {  # owner rework U3: the card has no ✕ — re-tapping the type's
                   # button IS tap-to-stop (StopGuard: >10 s dialog, ≤10 s silent)
  local type="$1" xml
  scroll_top
  tap_tag "dashboard/quick_action/$type" || { warn "no quick action for $type"; return 0; }
  sleep 2
  xml="$(dump_with_retry)" || return 1
  if bounds_of "$xml" "dashboard/stop_dialog" >/dev/null; then
    tap_tag "dashboard/stop_dialog/discard" || true
  fi
  wait_gone_tag "dashboard/stop_dialog" 8 || warn "stop dialog still open after discard ($type)"
}

# tap_node_lowest <attr> <value>: tap the LOWEST (max-y) node whose <attr>
# (text | content-desc) equals <value>. The native time picker's big selected
# hour/minute field carries the SAME content-desc as its dial label
# ("1 o'clock"); the dial label is always lower on screen, so it wins.
tap_node_lowest() {
  local attr="$1" val="$2" xml best="" by=-1 m x1 y1 x2 y2
  xml="$(dump_with_retry)" || return 1
  while IFS= read -r m; do
    [[ -z "$m" ]] && continue
    read -r x1 y1 x2 y2 <<< "$(printf '%s' "$m" | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')"
    if (( y1 > by )); then by=$y1; best="$x1 $y1 $x2 $y2"; fi
  done <<< "$(printf '%s' "$xml" | grep -oE "$attr=\"$val\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"")"
  [[ -n "$best" ]] || { printf 'ERROR: node not found: %s=%s\n' "$attr" "$val" >&2; return 1; }
  read -r x1 y1 x2 y2 <<< "$best"
  ash input tap "$(( (x1 + x2) / 2 ))" "$(( (y1 + y2) / 2 ))"
}

# node_center_lowest <attr> <value> <xml>: "x y" of the lowest matching node.
node_center_lowest() {
  local attr="$1" val="$2" xml="$3" by=-1 m x1 y1 x2 y2 cx="" cy=""
  while IFS= read -r m; do
    [[ -z "$m" ]] && continue
    read -r x1 y1 x2 y2 <<< "$(printf '%s' "$m" | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')"
    if (( y1 > by )); then by=$y1; cx=$(( (x1+x2)/2 )); cy=$(( (y1+y2)/2 )); fi
  done <<< "$(printf '%s' "$xml" | grep -oE "$attr=\"$val\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"")"
  [[ -n "$cx" ]] || return 1
  echo "$cx $cy"
}

# pick_time <value_tag> <HH24> <MM>: set a time row through the native Material
# time picker (owner rework U6: the ONLY time-editing affordance). AM/PM and the
# hour go through the dial (labels expose content-desc "N o'clock"; selecting an
# hour flips the dial to minutes); the exact minute is tapped at its angle on the
# minute dial (labels sit at every 5 min — "N minutes" — but the dial selects any
# minute from the touch position). Tags inside the sheet reach the dump since
# dialogTestTags() (QA finding #8 resolved).
pick_time() {
  local tag="$1" hh="$2" mm="$3" xml h12 period
  vtag "$tag" || return 1
  wait_text "Pick a time" 8 || return 1
  if (( 10#$hh >= 12 )); then period="PM"; else period="AM"; fi
  h12=$(( 10#$hh % 12 )); (( h12 == 0 )) && h12=12
  tap_row_text "$period" || true
  sleep 1
  tap_node_lowest "content-desc" "$h12 o'clock" || return 1
  sleep 1
  xml="$(dump_with_retry)" || return 1
  local top bottom x0 y0 x30 y30 cx cy r mx my
  top="$(node_center_lowest "content-desc" "0 minutes" "$xml")" || { warn "minute dial '0 minutes' label not found"; return 1; }
  bottom="$(node_center_lowest "content-desc" "30 minutes" "$xml")" || { warn "minute dial '30 minutes' label not found"; return 1; }
  read -r x0 y0 <<< "$top"; read -r x30 y30 <<< "$bottom"
  cx=$(( (x0 + x30) / 2 )); cy=$(( (y0 + y30) / 2 )); r=$(( (y30 - y0) / 2 ))
  read -r mx my <<< "$(python3 -c "import math; a=math.radians((int('$mm')+0.7)*6-90); print(int($cx+$r*math.cos(a)), int($cy+$r*math.sin(a)))")"
  # A tap snaps to the nearest 5-minute label; a short DRAG that ends on the
  # target angle selects the exact minute (verified on device).
  ash input swipe "$x30" "$y30" "$mx" "$my" 400
  sleep 1
  tap_row_text "OK" || return 1
  sleep 1
}

# reveal_tag <resource-id>: like vtag but ECHOES the node bounds ("x1 y1 x2 y2")
# once visible, so the caller can tap the already-resolved coordinates. A second
# dump after revealing is not safe: uiautomator's accessibility pass can RE-SHOW
# the IME for a still-focused text field (observed on device), the window
# re-shrinks mid-flight, and a tap computed from the second dump misses.
reveal_tag() {
  # IMPORTANT: read the IME state AFTER the dump, not before — the dump itself
  # re-shows the keyboard for a focused text field (its accessibility pass
  # re-focuses), so a check taken before the dump reports a stale state and
  # the "found" bounds end up BEHIND the keyboard, where the tap lands on
  # Gboard. Same for the swipe: it must stay inside the visible area.
  # $2 = allow_down (default 1): cards (list items) may drag BOTH directions;
  # sheets only UP (a down-drag dismisses them).
  local tag="$1" allow_down="${2:-1}" i b b2 y2 xml limit
  for i in 1 2 3 4 5 6 7 8; do
    # First pass: let a freshly-opened sheet/card FINISH its slide-in — bounds
    # captured mid-animation belong to a layout that no longer exists.
    [[ "$i" == 1 ]] && sleep 2
    xml="$(dump_with_retry 2>/dev/null)" || { sleep 2; continue; }
    limit=$(( SCREEN_H - 40 ))
    if ash dumpsys input_method 2>/dev/null | tr -d '\r' | grep -q 'mInputShown=true'; then
      limit=$(( SCREEN_H * 55 / 100 ))
    fi
    if b="$(bounds_of "$xml" "$tag")"; then
      y2="$(printf '%s' "$b" | awk '{print $4}')"
      if (( y2 <= limit )); then
        # Stability gate: re-dump after a beat and require UNCHANGED bounds —
        # coordinates captured mid-animation (sheets settling, list scrolls)
        # belong to a layout that no longer exists by tap time.
        sleep 1
        if xml="$(dump_with_retry 2>/dev/null)"; then
          if b2="$(bounds_of "$xml" "$tag")" && [[ "$b2" == "$b" ]]; then
            printf '%s\n' "$b"; return 0
          fi
        fi
      fi
    fi
    # Direction by kind (alternating directions OSCILLATED on the card — each
    # up-swipe undid the down-swipe — and never converged):
    # - cards (timeline list items): DOWN drags only — they scroll toward the top
    #   and bring the card header/save back after an IME scroll.
    # - sheets: UP drags only (a down-drag dismisses a bottom sheet).
    if (( allow_down == 1 )); then
      ash input swipe "$(( SCREEN_W / 2 ))" "$(( SCREEN_H * 38 / 100 ))" "$(( SCREEN_W / 2 ))" "$(( SCREEN_H * 58 / 100 ))" 300
    else
      ash input swipe "$(( SCREEN_W / 2 ))" "$(( SCREEN_H * 45 / 100 ))" "$(( SCREEN_W / 2 ))" "$(( SCREEN_H * 28 / 100 ))" 300
    fi
    sleep 2
  done
  warn "could not bring $tag on screen"
  return 1
}

# find_row_text <text> [like]: scroll the timeline (small drags) until the text
# is in the dump, then tap it. mode "like" taps the first node whose text
# CONTAINS the substring — required for the diaper row, whose swatch text is
# " Black" with a leading space (bounds_by_text_like). Rows below the fold never
# reach uiautomator, and the Today group is long after a full run — plain
# tap_row_text missed them.
find_row_text() {
  local text="$1" mode="${2:-exact}" i
  scroll_top   # newest rows live at the top; look there before scrolling down
  for i in 1 2 3 4 5 6; do
    if [[ "$mode" == "like" ]]; then
      tap_row_text_like "$text" && return 0
    else
      tap_row_text "$text" && return 0
    fi
    ash input swipe "$(( SCREEN_W / 2 ))" "$(( SCREEN_H * 62 / 100 ))" "$(( SCREEN_W / 2 ))" "$(( SCREEN_H * 42 / 100 ))" 300
    sleep 1.5
    # A mid-scroll dump can miss a row that is actually present — re-look at
    # rest once per cycle before scrolling further.
    if [[ "$mode" == "like" ]]; then
      tap_row_text_like "$text" && return 0
    else
      tap_row_text "$text" && return 0
    fi
  done
  warn "row text never found while scrolling: $text"
  return 1
}

# open_timer_card <TYPE>: tap the type's button expecting a FRESH card. A
# leftover running timer (previous run, before the app's refresh reconciles it
# away) turns that tap into tap-to-stop (U3) — discard it and tap again.
open_timer_card() {
  local type="$1" xml
  tap_tag "dashboard/quick_action/$type" || return 1
  sleep 2
  if xml="$(dump_with_retry 2>/dev/null)" && bounds_of "$xml" "dashboard/stop_dialog" >/dev/null; then
    warn "leftover $type timer — discarding it and starting fresh"
    tap_tag "dashboard/stop_dialog/discard" || true
    sleep 2
    tap_tag "dashboard/quick_action/$type" || return 1
  fi
  wait_for_tag "dashboard/panel/$type" 15 2 >/dev/null
}

# sheet_time_value <xml> <n>: the Nth "Today HH:MM"-style value on the edit
# sheet (1 = START/TIME, 2 = END) — unique to the sheet (rows show bare HH:mm).
sheet_time_value() {
  printf '%s' "$1" | grep -oE 'text="[^"]+ [0-9]{2}:[0-9]{2}"' | sed -n "${2}p" | sed -E 's/^text="([^"]*)".*/\1/'
}

# ------------------------------------------------------------- API cleanup ---

api_cleanup() {
  (( CLEANED == 1 )) && return 0
  CLEANED=1
  step "Cleanup — deleting THIS run's test entries + leftover app timers via the API"
  QA_API_SH="$SCRIPT_DIR/api.sh" python3 - <<'PYEOF'
import json, os, subprocess

BASE = os.environ.get("BB_BASE_URL", "http://localhost:8000")
TOKFILE = os.environ.get("BB_TOKEN_FILE", "/tmp/bb_token")
API_SH = os.environ["QA_API_SH"]

MY_NOTES = ("QA capture note panel", "QA offline pending entry", "QA serverdown pending entry")

def api(method, path, body=None):
    cmd = ["bash", API_SH, method, path]
    if body is not None:
        cmd.append(json.dumps(body))
    env = dict(os.environ, BB_BASE_URL=BASE, BB_TOKEN_FILE=TOKFILE)
    p = subprocess.run(cmd, capture_output=True, text=True, env=env)
    # api.sh exits 1 when the response body is empty (e.g. HTTP 204 deletes) —
    # a 2xx status line on stderr still means success.
    if p.returncode != 0 and "HTTP 2" not in p.stderr:
        print(f"  api {method} {path} failed: {p.stderr.strip()}", flush=True)
        return None
    try:
        return json.loads(p.stdout) if p.stdout.strip() else None
    except json.JSONDecodeError:
        return None

def num(x):
    try:
        return float(x)
    except (TypeError, ValueError):
        return None

def delete_where(path, pred, label):
    data = api("GET", path)
    if not data or not isinstance(data, dict):
        print(f"  no data from {path}", flush=True)
        return
    for item in data.get("results", []):
        if pred(item):
            print(f"  deleting {label} id={item.get('id')}", flush=True)
            api("DELETE", f"{path}{item['id']}/")

delete_where("/api/notes/", lambda n: n.get("note") in MY_NOTES, "QA note")
delete_where("/api/sleep/", lambda s: s.get("notes") in ("QA-capture-A", "QA-capture-B"), "QA sleep")
delete_where("/api/changes/", lambda c: num(c.get("amount")) == 73.0, "QA diaper (amount 73)")
delete_where("/api/feedings/", lambda f: num(f.get("amount")) == 149.0, "QA feeding (amount 149)")
# leftover app server-timers ("<Type>-BBapp:<n>") resurrect as running timers on
# every app launch — purge them so the end state has no running timers.
delete_where("/api/timers/", lambda t: "-BBapp:" in str(t.get("name", "")), "app server timer")
print("  cleanup done", flush=True)
PYEOF
}

# ------------------------------------------------------------ restore/trap ---

restore_state() {
  local rc=$?
  trap - EXIT
  set +e
  printf '\n==> restoring end state (rc=%s)\n' "$rc"
  # 1. toggles first — everything else may need the network back
  if (( OFFLINE_ON )); then  "$SCRIPT_DIR/offline.sh" off  || warn "could not turn airplane mode off"; fi
  if (( SERVER_STOPPED )); then "$SCRIPT_DIR/server.sh" start || warn "could not restart server"; fi
  # 2. app to the foreground
  if [[ -n "$LAUNCHER_COMP" ]]; then ash am start -n "$LAUNCHER_COMP" >/dev/null 2>&1; sleep 3; fi
  # 3. no running timers (best-effort): strip discard, then purge server timers
  local t xml
  for t in FEEDING SLEEP PUMPING TUMMY_TIME; do
    xml="$(dump_with_retry 2>/dev/null)" || break
    if bounds_of "$xml" "dashboard/timer/$t" >/dev/null 2>&1; then
      printf '    discarding leftover %s timer\n' "$t"
      discard_timer "$t"
    fi
  done
  api_cleanup
  # 4. relaunch clean (a purged timer must not resurrect from a stale process)
  if [[ -n "$LAUNCHER_COMP" ]]; then
    ash am force-stop "$PKG"
    sleep 2
    ash am start -n "$LAUNCHER_COMP" >/dev/null 2>&1
    wait_for_tag "dashboard" 30 2 >/dev/null || warn "no dashboard at end state"
  fi
  # 5. active child back to Milo (best-effort)
  xml="$(dump_with_retry 2>/dev/null || true)"
  if [[ -n "$xml" ]] && bounds_of "$xml" "dashboard/child_switcher" >/dev/null 2>&1; then
    local chip
    chip="$(text_of "$xml" "dashboard/child_switcher" 2>/dev/null || true)"
    if [[ "$chip" == Zoe* ]]; then
      printf '    switching active child back to Milo\n'
      tap_tag "dashboard/child_switcher" || true
      wait_text "Milo Rivera" 6 && tap_row_text "Milo Rivera"
      sleep 1
    fi
  fi
  if (( rc == 0 && FAILURES > 0 )); then rc=1; fi
  printf '==> capture-flows done (failures=%s, rc=%s)\n' "$FAILURES" "$rc"
  exit "$rc"
}
B_START_HH=""; B_START_MM=""   # group 05: sleep B's original START (resolve step)
trap restore_state EXIT

# ----------------------------------------------------------- precondition ---

purge_server_timers() {  # leftover "<Type>-BBapp:<n>" timers resurrect as running
                         # timers on app launch — purging normalizes start state.
  QA_API_SH="$SCRIPT_DIR/api.sh" python3 - <<'PYEOF'
import json, os, subprocess

API_SH = os.environ["QA_API_SH"]

def api(method, path, body=None):
    cmd = ["bash", API_SH, method, path]
    if body is not None:
        cmd.append(json.dumps(body))
    p = subprocess.run(cmd, capture_output=True, text=True)
    if p.returncode != 0:
        return None
    try:
        return json.loads(p.stdout) if p.stdout.strip() else None
    except json.JSONDecodeError:
        return None

data = api("GET", "/api/timers/")
if isinstance(data, dict):
    for t in data.get("results", []):
        if "-BBapp:" in str(t.get("name", "")):
            print(f"  purging leftover server timer id={t.get('id')} ({t.get('name')})", flush=True)
            api("DELETE", f"/api/timers/{t['id']}/")
PYEOF
}

check_env() {
  step "Preconditions"
  ash echo >/dev/null 2>&1 || die "device $SERIAL not reachable via adb"
  "$SCRIPT_DIR/server.sh" wait >/dev/null 2>&1 || die "Baby Buddy server not answering (scripts/qa/server.sh wait)"
  ash pm list packages 2>/dev/null | tr -d '\r' | grep -q "package:$PKG" || die "$PKG not installed"
  # disable animations — uiautomator-friendly (same as fresh-install-login.sh)
  ash settings put global window_animation_scale 0
  ash settings put global transition_animation_scale 0
  ash settings put global animator_duration_scale 0
  local size
  size="$(ash wm size 2>/dev/null | tr -d '\r' | grep -oE '[0-9]+x[0-9]+' | tail -1)"
  [[ "$size" == *x* ]] && { SCREEN_W="${size%x*}"; SCREEN_H="${size#*x}"; }
  LAUNCHER_COMP="$(ash cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$PKG" 2>/dev/null | tr -d '\r' | grep -v '^$' | tail -1 || true)"
  [[ "$LAUNCHER_COMP" == */* ]] || die "could not resolve launcher activity for $PKG"
  printf '    device %s (%sx%s), app %s, launcher %s\n' "$SERIAL" "$SCREEN_W" "$SCREEN_H" "$PKG" "$LAUNCHER_COMP"
  purge_server_timers || warn "server timer purge failed (continuing)"
}

# ------------------------------------------------------- group 01: login ---

do_login_drive() {
  # Assumes the login screen is visible. Captures initial + cleartext + error
  # banner (cheap: one deliberate wrong-password round-trip), then signs in.
  # Tolerates the post-sign-out ANR ("Wait") while the app clears its DB.
  local i xml
  for i in $(seq 1 20); do
    if xml="$(dump_with_retry 2>/dev/null)"; then
      if bounds_of "$xml" "login/server_url" >/dev/null; then break; fi
      if printf '%s' "$xml" | grep -q "isn't responding"; then
        warn "ANR dialog detected — tapping Wait"
        tap_text "$xml" "Wait" || true
        sleep 3
      fi
    fi
    (( i == 20 )) && { warn "login screen never appeared"; return 1; }
    sleep 3
  done
  shot 01-login-01-initial

  step "01: cleartext dialog + error banner (deliberate wrong password first)"
    type_into "login/server_url" "http://10.0.2.2:8000" || warn "typing failed"
    type_into "login/username" "admin" || warn "typing failed"
    type_into "login/password" "definitely-wrong" || warn "typing failed"
  tap_tag "login/submit" || { warn "login/submit missing"; return 1; }
  # The cleartext dialog's own testTag never reaches the uiautomator dump (a
  # Compose dialog window exposes only its text nodes — and while it is up the
  # login fields are absent) — detect it via the unique "Continue" text node.
  if wait_continue_and_tap 60; then
    shot 01-login-02-cleartext-dialog
  else
    warn "cleartext dialog did not appear (may be suppressed for a pre-acknowledged URL)"
  fi

  if wait_for_tag "login/error_banner" 60 2 >/dev/null; then
    shot 01-login-03-error-banner
  else
    warn "error banner not captured (server rejected nothing?)"
  fi

  step "01: sign in with correct credentials"
    type_into "login/password" "admin" || warn "typing failed"
  tap_tag "login/submit" || { warn "login/submit missing"; return 1; }
  wait_continue_and_tap 60 || true
  wait_for_tag "dashboard" 90 2 >/dev/null || { warn "dashboard never appeared after login"; return 1; }
  printf '    logged in, dashboard visible\n'
}

# wait_continue_and_tap <timeout_s>: poll for the cleartext dialog's Continue
# text node and tap it. 0 when tapped, 1 on timeout.
wait_continue_and_tap() {
  local timeout="$1" i xml
  for i in $(seq 1 $(( timeout / 3 ))); do
    if xml="$(dump_with_retry 2>/dev/null)"; then
      if bounds_by_text "$xml" "Continue" >/dev/null; then
        tap_row_text "Continue" || true
        sleep 2
        return 0
      fi
    fi
    sleep 3
  done
  return 1
}

g01_login() {
  step "Group 01 — login states"
  open_app || true
  # Deterministic start: a fresh process (the previous one may be mid-ANR,
  # carrying restored timers, or showing a stale dialog).
  ash am force-stop "$PKG"
  sleep 3
  ash am start -n "$LAUNCHER_COMP" >/dev/null 2>&1 || true
  local state="" xml i
  for i in $(seq 1 15); do
    if xml="$(dump_with_retry 2>/dev/null)"; then
      if   bounds_of "$xml" "dashboard"       >/dev/null; then state="dashboard"; break
      elif bounds_of "$xml" "login/server_url" >/dev/null; then state="login";     break
      elif bounds_of "$xml" "dashboard/panel/close" >/dev/null; then
        # A create sheet restored from a previous run's draft (process-death restore is a
        # feature) — it is its own window and hides the dashboard tags. Close it.
        warn "restored create sheet at launch — closing it"
        tap_tag "dashboard/panel/close" || true
        sleep 2
      elif printf '%s' "$xml" | grep -q "isn't responding"; then
        warn "ANR dialog detected — tapping Wait"
        tap_text "$xml" "Wait" || true
        sleep 3
      fi
    fi
    sleep 2
  done
  if [[ -z "$state" ]]; then
    warn "neither dashboard nor login visible — dump head:"
    printf '%s\n' "$(ash cat /sdcard/bb-ui.xml 2>/dev/null | tr -d '\r' | grep -oE '(resource-id="[^"]{1,40}"|text="[^"]{1,40}")' | head -8)"
    return 1
  fi

  if [[ "$state" == "dashboard" ]]; then
    step "01: already logged in — signing out via the app to reach the login states"
    # The first post-boot dump can catch the dashboard mid-composition (grid
    # present, header not yet) — retry the tap a few times before giving up.
    local oi ov_ok=0
    for oi in 1 2 3 4 5; do
      # A restored create sheet (persisted open draft) can cover the header —
      # close it before each retry.
      xml="$(dump_with_retry 2>/dev/null || true)" || xml=""
      if [[ -n "$xml" ]] && bounds_of "$xml" "dashboard/panel/close" >/dev/null 2>&1; then
        warn "restored create sheet covering the header — closing it"
        tap_tag "dashboard/panel/close" || true
        sleep 2
      fi
      if tap_tag "dashboard/overflow" 2>/dev/null; then ov_ok=1; break; fi
      sleep 3
    done
    (( ov_ok )) || { warn "overflow missing"; return 1; }
    local xml2
    xml2="$(dump_with_retry)" || return 1
    tap_text "$xml2" "Sign out" || { warn "Sign out menu item missing"; ash input keyevent 4; return 1; }
    # confirmation dialog ("Sign out?" / Sign out / Cancel)
    if wait_text "Sign out?" 10; then
      xml2="$(dump_with_retry)" || return 1
      # dropdown is closed here, so the only "Sign out" on screen is the dialog button
      tap_text "$xml2" "Sign out" || ash input keyevent 4
    else
      warn "sign-out confirmation dialog not found"
    fi
  fi
  do_login_drive
}

# --------------------------------------------------- group 02: dashboard ---

switch_child() {  # switch_child <Full Name>: open the switcher, pick the child
  tap_tag "dashboard/child_switcher" || return 1
  wait_text "$1" 8 || { warn "child row not found: $1"; ash input keyevent 4; return 1; }
  tap_row_text "$1"
  sleep 2
}

ensure_milo() {
  local xml chip
  xml="$(dump_with_retry 2>/dev/null)" || return 0
  chip="$(text_of "$xml" "dashboard/child_switcher" 2>/dev/null || true)"
  if [[ "$chip" == Zoe* ]]; then switch_child "Milo Rivera" || true; fi
}

g02_dashboard() {
  step "Group 02 — dashboard states"
  open_app || return 1
  ensure_milo
  restart_app || return 1   # fresh timeline render (T1 workaround) with seeded data
  shot 02-dashboard-01-idle

  step "02: overflow menu"
  if tap_tag "dashboard/overflow"; then
    sleep 1
    shot 02-dashboard-02-overflow-menu
    ash input keyevent 4   # dismiss the dropdown
    sleep 1
  else
    warn "dashboard/overflow not found"
  fi

  step "02: child switcher"
  if tap_tag "dashboard/child_switcher"; then
    sleep 1
    shot 02-dashboard-03-child-switcher-open
    if tap_row_text "Zoe Rivera"; then
      sleep 3
      shot 02-dashboard-04-child-zoe
    else
      warn "Zoe Rivera row not found (needs 2 children on the server)"
    fi
  else
    warn "dashboard/child_switcher not found"
  fi
  switch_child "Milo Rivera" || true   # back to Milo for the create flows
}

# ------------------------------------------------ group 03: quick actions ---

g03_quickactions() {
  step "Group 03 — quick actions (six panels + dialogs)"
  open_app || return 1
  scroll_top

  step "03: FEEDING panel + type/method cascade"
  if open_timer_card FEEDING; then
    shot 03-quickactions-01-feeding-panel
    vtag "dashboard/panel/feeding_type/formula" 1 && sleep 1
    shot 03-quickactions-02-feeding-cascade   # methods drop to bottle/parent fed/self fed
    vtag "dashboard/panel/feeding_type/breast milk" 1 || true
    vtag "dashboard/panel/feeding_method/bottle" 1 || true
    vtype "dashboard/panel/amount" "149" && hide_kb
    save_panel "FEEDING" || true
    wait_gone_tag "dashboard/panel/FEEDING" 15 || warn "feeding panel did not close after save"
  else
    warn "FEEDING quick action failed"
  fi
  scroll_top

  step "03: DIAPER sheet + extra options"
  if tap_tag "dashboard/quick_action/CHANGE" && wait_for_tag "dashboard/panel/CHANGE" 15 2 >/dev/null; then
    sleep 2   # let the sheet finish its slide-in before any coordinates are read
    # Toggle rows are driven by TEXT taps: the sheet is its own window whose
    # content settles late, and tag-based taps raced its slide-in (bounds up to
    # 130px off). Text nodes in the sheet's window are stable once it is at
    # rest, and while the sheet is up they are unambiguous.
    shot 03-quickactions-03-diaper-sheet   # Save disabled: Wet/Solid both off
    # Wet must END UP ON: the create draft persists across sessions (a previous
    # run's leftover draft can open this sheet with Wet ALREADY checked, and a
    # blind tap would switch it OFF, leaving Save disabled forever). The hint
    # "Choose at least one of Wet or Solid." is the app's own validity signal —
    # tap Wet only while it shows, then require it to disappear.
    local wi hint_xml wet_ok=0
    for wi in 1 2 3; do
      hint_xml="$(dump_with_retry 2>/dev/null)" || hint_xml=""
      if ! printf '%s' "$hint_xml" | grep -q 'Choose at least one of Wet or Solid'; then wet_ok=1; break; fi
      vtag "dashboard/panel/wet" 0 || warn "wet toggle tap failed"
      sleep 1
    done
    (( wet_ok )) || warn "wet toggle did not turn on (Save stays disabled)"
    vtag "dashboard/panel/extra_options" 0 && sleep 1.5
    shot 03-quickactions-04-diaper-extra-options   # color chips + amount
    vtag "dashboard/panel/color/black" 0 || warn "black chip tap failed"
    vtype "dashboard/panel/amount" "73" && hide_kb
    # Collapse so Save is reachable (the row reads "Hide extra options" while
    # expanded — an exact-match tap on "Extra options" would silently miss).
    vtag "dashboard/panel/extra_options" 0 || true
    save_panel "CHANGE" || true
    wait_gone_tag "dashboard/panel/CHANGE" 15 || warn "diaper panel did not close after save"

    # --- CANARY A1: save must be visible on the timeline within 12 s, no restart,
    # no refresh. The color swatch label "Black" is our diaper's unique marker
    # (v0.4 headlines no longer repeat the color; seed data has no black diapers).
    # MATCH BY SUBSTRING: TimelineSection composes the swatch Text as " " + label
    # (the space is the gap after the color dot), so the dump carries
    # text=" Black" and an exact text="Black" NEVER matches — this exact-match
    # grep is what silently failed every persona-round run while the save itself
    # succeeded. A FAILED dump (UiAutomation transiently busy: insert animation,
    # drain, chip settle) does NOT count against the app — only a successful dump
    # missing the row proves absence. Observed on device: the row renders within
    # ~1 s.
    local a1=0 tries xmlA
    scroll_top   # the (possibly still-open/tall) panel pushes the timeline below the fold
    for tries in 1 2 3 4 5 6 7 8 9 10 11 12; do
      if xmlA="$(dump_with_retry 2>/dev/null)"; then
        if printf '%s' "$xmlA" | grep -q 'text="Today"' \
           && printf '%s' "$xmlA" | grep -q 'text="[^"]*Black"'; then a1=1; break; fi
      fi
      sleep 1
    done
    assert_chk "$a1" "A1 save-visibility: our black-diaper row (swatch 'Black') under Today within 12 s of saving a diaper (no restart/refresh)"
  else
    warn "DIAPER quick action failed"
    assert_chk 0 "A1 save-visibility (diaper save step failed — canary not exercised)"
  fi
  scroll_top

  step "03: duplicate guard (diaper saved seconds ago -> save again)"
  if tap_tag "dashboard/quick_action/CHANGE" && wait_for_tag "dashboard/panel/CHANGE" 15 2 >/dev/null; then
    sleep 2
    vtag "dashboard/panel/wet" 0 || true
    save_panel "CHANGE" no-accept-dup || true   # pops the duplicate dialog - captured next
    if wait_for_tag "dashboard/duplicate_dialog" 10 2 >/dev/null; then
      shot 03-quickactions-05-duplicate-guard
      ash input keyevent 4   # dismiss WITHOUT adding (no cleanup needed)
      sleep 1
    else
      warn "duplicate dialog did not appear (was the last diaper <4 min ago?)"
    fi
    tap_tag "dashboard/panel/close" || ash input keyevent 4
  else
    warn "duplicate-guard diaper sheet failed"
  fi
  scroll_top

  step "03: SLEEP running card (below the buttons), then >10s stop-guard dialog"
  if open_timer_card SLEEP; then
    shot 03-quickactions-06-sleep-running   # live counter visible
    sleep 12                                # must exceed 10 s for the stop guard
    scroll_top
    if tap_tag "dashboard/quick_action/SLEEP"; then   # U3: tap-to-stop (no ✕ on the card)
      if wait_for_tag "dashboard/stop_dialog" 10 2 >/dev/null; then
        shot 03-quickactions-07-sleep-stopguard   # Save emphasized
        tap_tag "dashboard/stop_dialog/discard" || true
      else
        warn "stop dialog did not appear for sleep"
      fi
    else
      warn "could not re-tap the running sleep quick action"
    fi
    wait_gone_tag "dashboard/panel/SLEEP" 10 || true
  else
    warn "SLEEP quick action failed"
  fi
  scroll_top

  step "03: NOTES panel with text"
  if tap_tag "dashboard/quick_action/NOTE" && wait_for_tag "dashboard/panel/NOTE" 15 2 >/dev/null; then
    vtype "dashboard/panel/text" "QA capture note panel" && hide_kb
    shot 03-quickactions-08-note-with-text
    save_panel "NOTE" || true
    wait_gone_tag "dashboard/panel/NOTE" 15 || true
  else
    warn "NOTE quick action failed"
  fi
  scroll_top

  step "03: PUMPING panel (discarded <10s, silently)"
  if open_timer_card PUMPING; then
    shot 03-quickactions-09-pumping-panel
    discard_timer "PUMPING"
  else
    warn "PUMPING quick action failed"
  fi
  scroll_top

  step "03: TUMMY TIME panel (discarded <10s, silently)"
  if open_timer_card TUMMY_TIME; then
    shot 03-quickactions-10-tummytime-panel
    discard_timer "TUMMY_TIME"
  else
    warn "TUMMY_TIME quick action failed"
  fi
  scroll_top
}

# -------------------------------------------------------- group 04: edit ---

g04_edit() {
  step "Group 04 — quick edit sheet"
  open_app || return 1
  restart_app || true   # T1 workaround: only a restart renders the g03 rows
  scroll_top

  step "04: feeding sheet + START moved one hour earlier via the native picker"
  if find_row_text "Bottle · 149 mL" && wait_for_tag "edit/sheet" 10 2 >/dev/null; then
    expand_sheet
    local xml before after new_hm changed hh mm
    xml="$(dump_with_retry)" || return 1
    before="$(sheet_time_value "$xml" 1)"
    shot 04-edit-01-feeding-sheet   # START + END native-picker rows visible (U6)
    hh="${before##* }"; mm="${hh#*:}"; hh="${hh%%:*}"
    if [[ "$hh" =~ ^[0-9]+$ ]]; then
      pick_time "edit/sheet/time_start" "$(( (10#$hh + 23) % 24 ))" "$mm" || warn "start picker flow failed"
    else
      warn "could not parse START value ($before)"
    fi
    sleep 1
    xml="$(dump_with_retry)" || return 1
    after="$(sheet_time_value "$xml" 1)"
    shot 04-edit-02-feeding-picked   # value visibly changed
    if [[ -n "$before" && -n "$after" && "$before" != "$after" && "$after" == Today* ]]; then changed=1; else changed=0; fi
    # "Today*" guards a date-picker regression that produced TOMORROW for evening
    # times (UTC round-trip); an hour-earlier move always stays on the same day
    # (except within the first hour after midnight — accepted edge).
    assert_chk "$changed" "A2a edit sheet value visibly changed via the native time picker, same day ($before -> $after)"
    new_hm="${after##* }"
    # Save by text (dialog-window tags are invisible to uiautomator, QA finding #8).
    if tap_row_text "Save"; then
      wait_gone_tag "edit/sheet" 10 || warn "edit sheet did not close"
      # --- CANARY A2: the row must show the nudged time within 10 s (no restart).
      # Dump failures are infra, not app evidence (see A1).
      local a2=0 tries xmlB
      for tries in 1 2 3 4 5 6 7 8 9 10; do
        if xmlB="$(dump_with_retry 2>/dev/null)"; then
          if [[ -n "$new_hm" ]] && printf '%s' "$xmlB" | grep -q "text=\"$new_hm\""; then a2=1; break; fi
        fi
        sleep 1
      done
      assert_chk "$a2" "A2b timeline row updated in place after edit save (shows $new_hm)"
    else
      warn "edit/save not reachable"
      assert_chk 0 "A2b timeline row updated after edit save (save button missing — step failed)"
    fi
  else
    warn "feeding row 'Bottle · 137 mL' not found (was group 03 skipped?)"
    assert_chk 0 "A2a/A2b edit canaries (feeding row missing — step failed)"
  fi

  step "04: diaper sheet + delete-confirm dialog"
  if find_row_text "Black" like && wait_for_tag "edit/sheet" 10 2 >/dev/null; then
    expand_sheet
    shot 04-edit-03-diaper-sheet   # Wet/Solid toggles + TIME row
    if vtag "edit/delete" && wait_for_tag "edit/delete_confirm" 10 2 >/dev/null; then
      shot 04-edit-04-delete-confirm
      tap_row_text "Cancel" || true   # capture only; cleanup deletes via API
    else
      warn "delete confirm dialog not reached"
    fi
    tap_row_text "✕" || ash input keyevent 4   # close the sheet
    wait_gone_tag "edit/sheet" 8 || true
  else
    warn "diaper row (black swatch) not found (was group 03 skipped?)"
  fi
}

# -------------------------------------------------- group 05: sync states ---

offline_note() {  # create a note while offline; 0 if the panel save fired
  tap_tag "dashboard/quick_action/NOTE" || return 1
  wait_for_tag "dashboard/panel/NOTE" 15 2 >/dev/null || return 1
  vtype "dashboard/panel/text" "$1" && hide_kb
  vtag "dashboard/panel/save" || return 1
  wait_gone_tag "dashboard/panel/NOTE" 15 || true
}

sleep_with_note() {  # start a sleep timer with a marker note, run >10s (or
                     # $2 seconds), save via the card's check Save (same draft;
                     # accepts the duplicate guard between the two sleeps)
  local note="$1" track="${2:-12}"
  open_timer_card SLEEP || return 1
  vtype "dashboard/panel/note" "$note" && hide_kb
  sleep "$track"                        # >10 s so the stop guard applies
  scroll_top
  save_panel "SLEEP" || return 1
  wait_gone_tag "dashboard/panel/SLEEP" 10 || true
  return 0
}

wait_note_on_server() {  # host-side truth: the note drained to the server
  local text="$1" timeout="${2:-60}" i
  for i in $(seq 1 "$((timeout / 5))"); do
    if bash "$SCRIPT_DIR/api.sh" GET /api/notes/ 2>/dev/null \
       | python3 -c "import json,sys; d=json.load(sys.stdin); sys.exit(0 if any(n.get('note')=='$text' for n in d.get('results',[])) else 1)" 2>/dev/null; then
      return 0
    fi
    sleep 5
  done
  return 1
}

g05_sync_states() {
  step "Group 05 — offline / pending / failed / synced"
  open_app || return 1

  step "05: offline (airplane) -> note -> PENDING badge (visible after relaunch, spec 6.3.4)"
  if "$SCRIPT_DIR/offline.sh" on; then OFFLINE_ON=1; else warn "airplane mode ON failed"; return 1; fi
  if offline_note "QA offline pending entry"; then
    restart_app || warn "restart (offline) failed"
    if wait_for_tag "dashboard/badge_pending" 20 2 >/dev/null; then
      shot 05-sync-states-01-offline-pending
    else
      warn "offline pending badge not captured"
    fi
  else
    warn "offline note could not be saved"
  fi

  step "05: back online, then server stopped -> another pending entry"
  if "$SCRIPT_DIR/offline.sh" off; then OFFLINE_ON=0; else warn "airplane mode OFF failed"; fi
  "$SCRIPT_DIR/server.sh" stop && SERVER_STOPPED=1
  sleep 2
  if offline_note "QA serverdown pending entry"; then
    restart_app || warn "restart (server-down) failed"
    if wait_for_tag "dashboard/badge_pending" 20 2 >/dev/null; then
      shot 05-sync-states-02-serverdown-pending
    else
      warn "server-down pending badge not captured"
    fi
  else
    warn "server-down note could not be saved"
  fi

  step "05: server returns -> drain (verified host-side) -> badges clear"
  if "$SCRIPT_DIR/server.sh" start; then SERVER_STOPPED=0; else warn "server start failed"; fi
  if wait_note_on_server "QA offline pending entry" 60 \
     && wait_note_on_server "QA serverdown pending entry" 60; then
    printf '    both queued notes reached the server\n'
  else
    warn "queued notes did not drain within 60 s"
  fi
  restart_app || true
  shot 05-sync-states-03-synced-badge-cleared

  step "05: overlapping sleep -> FAILED badge + failure dialog"
  # A and B are created ONLINE (both sync). Then, OFFLINE, B's START is moved
  # one minute EARLIER: start-moves are DURATION-PRESERVING (spec §5), so with
  # A tracked ~210 s and B starting right after it, B's new window lands
  # strictly inside A's even when the minute-dial drag lands ±2 (a 90 s A made
  # the chain knife-edge: ONLY original−1min overlapped, so every dial jitter
  # silently killed the canary) — a guaranteed overlap, always in the PAST (an
  # END+1h edit of a just-created entry lands in the future and validation
  # correctly blocks the save — the scenario never reached the server). The
  # queued PATCH is rejected at replay (overlap) and B ends FAILED —
  # resolution path captured below.
  if sleep_with_note "QA-capture-A" 210; then printf '    sleep A synced\n'
  else scroll_top; sleep 2
       if sleep_with_note "QA-capture-A" 210; then printf '    sleep A synced (retry)\n'; else warn "sleep A failed"; fi
  fi
  if sleep_with_note "QA-capture-B"; then printf '    sleep B synced\n'
  else scroll_top; sleep 2
       if sleep_with_note "QA-capture-B"; then printf '    sleep B synced (retry)\n'; else warn "sleep B failed"; fi
  fi
  restart_app || true   # rows visible
  scroll_top
  # save_edit_sheet: reveal the edit sheet's Save, tap it, and REQUIRE the sheet
  # to close — the tap races the tick/IME re-layout more often here than
  # anywhere else, so verify and retry. A silently-missed save breaks the whole
  # overlap chain (B never moves -> never overlaps -> never FAILED), which is
  # exactly what the previous one-shot reveal+tap did (2026-10-19 gate run:
  # chain broke silently, "overlap -> FAILED badge" canary failed).
  save_edit_sheet() {
    local rb ri
    for ri in 1 2 3; do
      rb="$(reveal_tag "edit/save" 0 2>/dev/null)" || { warn "edit save not visible"; return 1; }
      ash input tap $(( $(echo "$rb" | awk '{print int(($1+$3)/2)}') )) $(( $(echo "$rb" | awk '{print int(($2+$4)/2)}') ))
      sleep 3
      if ! bounds_of "$(dump_with_retry 2>/dev/null || true)" "edit/sheet" >/dev/null; then return 0; fi
    done
    warn "edit sheet would not close after save"
    return 1
  }
  # b_overlaps_a_on_server: HOST-SIDE truth — does any QA-capture-B sleep row
  # intersect any QA-capture-A row on the server right now? The whole FAILED
  # badge chain only fires if B's edited window actually lands inside A's (A is
  # tracked only ~210 s now), so before retrying/failing we ask the server.
  b_overlaps_a_on_server() {
    bash "$SCRIPT_DIR/api.sh" GET /api/sleep/ 2>/dev/null | python3 -c '
import json, sys
from datetime import datetime

def p(s):
    return datetime.strptime(s.replace("Z", "+0000"), "%Y-%m-%dT%H:%M:%S%z")

try:
    d = json.load(sys.stdin)
except Exception:
    print("NO-OVERLAP")
    raise SystemExit
a = [r for r in d.get("results", []) if r.get("notes") == "QA-capture-A"]
b = [r for r in d.get("results", []) if r.get("notes") == "QA-capture-B"]
hit = "NO-OVERLAP"
for ra in a:
    for rb in b:
        try:
            a0, a1 = p(ra["start"]), p(ra["end"])
            b0, b1 = p(rb["start"]), p(rb["end"])
        except Exception:
            continue
        if b0 < a1 and a0 < b1:
            hit = "OVERLAP"
print(hit)
' 2>/dev/null
  }

  # overlap_edit_once: OFFLINE, move B's START to (original start − 1 min) — a
  # duration-preserving move (spec §5) that puts B strictly inside A.s ~210 s
  # window, so the drain's PATCH replay is guaranteed to be rejected. The
  # minute-dial DRAG lands ±1 (observed), and a −2 landing puts B BEFORE A —
  # no overlap, nothing to reject — so the picked value is VERIFIED against the
  # sheet's START row and re-picked with a compensated target (max 4 tries);
  # any landing 1..3 min before the original is accepted (all inside A).
  overlap_edit_once() {
    if ! "$SCRIPT_DIR/offline.sh" on; then warn "airplane mode ON failed"; return 1; fi
    OFFLINE_ON=1
    sleep 5
    if ! { find_row_text "QA-capture-B" && wait_for_tag "edit/sheet" 10 2 >/dev/null; }; then
      warn "could not edit sleep B (row text missing?)"
      "$SCRIPT_DIR/offline.sh" off && OFFLINE_ON=0
      return 1
    fi
    expand_sheet
    local xmlE startv shh smm
    xmlE="$(dump_with_retry)" || true
    startv="$(sheet_time_value "$xmlE" 1)"; shh="${startv##* }"; smm="${shh#*:}"; shh="${shh%%:*}"
    if [[ ! "$shh" =~ ^[0-9]+$ ]]; then
      warn "could not parse B's START value ($startv)"
      "$SCRIPT_DIR/offline.sh" off && OFFLINE_ON=0
      return 1
    fi
    # Remember the ORIGINAL start once: the resolve step edits back to
    # original+1min, and a retry must re-aim at the same absolute target (the
    # row may already carry a shifted value from a first, missed pick).
    if [[ -z "$B_START_HH" ]]; then B_START_HH="$shh"; B_START_MM="$smm"; fi
    local w_hh=$(( 10#$B_START_HH )) w_mm=$(( 10#$B_START_MM - 1 ))
    if (( w_mm < 0 )); then w_mm=59; w_hh=$(( (w_hh + 23) % 24 )); fi
    # Accept band: the saved START must land STRICTLY inside A's window. A is
    # tracked 210 s and B ~12 s, so any landing 1..3 minutes EARLIER than the
    # original start overlaps. original−1min is the aim, but the dial drag's
    # error is ±1..3 min and NOT reproducible, and insisting on exactly −1
    # re-picks away in-band landings (observed 2026-10-19: targets 17/14/18,
    # landings 20/16/20 — the 16 WAS inside A's window and got re-picked away).
    local pi t_hh="$w_hh" t_mm="$w_mm" v di got_hh got_mm ok=0 d e
    for pi in 1 2 3 4; do
      pick_time "edit/sheet/time_start" "$t_hh" "$t_mm" || { warn "start picker flow failed"; break; }
      v=""
      for di in 1 2 3; do
        xmlE="$(dump_with_retry 2>/dev/null || true)"
        v="$(sheet_time_value "$xmlE" 1)"
        [[ "$v" == Today* ]] && break
        sleep 1
      done
      got_hh="${v##* }"; got_mm="${got_hh#*:}"; got_hh="${got_hh%%:*}"
      if [[ "$got_hh" =~ ^[0-9]+$ ]]; then
        # d = minutes by which the landing PRECEDES the original start (mod day)
        d=$(( (10#$B_START_HH * 60 + 10#$B_START_MM - (10#$got_hh * 60 + 10#$got_mm) + 1440) % 1440 ))
        if (( d >= 1 && d <= 3 )); then
          ok=1
          break
        fi
      fi
      warn "start pick landed at '$v' (aim $w_hh:$(printf '%02d' "$w_mm"), need original−1..−3) — re-picking"
      # Compensate by the drag error OBSERVED AGAINST THE TARGET JUST USED
      # (e = landed − target, clamped to ±30 to ignore garbage): aim at wanted
      # shifted opposite the error. A systematic error converges on the second
      # try; random error just gets another independent chance.
      e=0
      if [[ "$got_hh" =~ ^[0-9]+$ ]]; then
        e=$(( (10#$got_hh * 60 + 10#$got_mm - (10#$t_hh * 60 + 10#$t_mm) + 1440) % 1440 ))
        (( e > 720 )) && e=$(( e - 1440 ))
        (( e > 30 || e < -30 )) && e=0
      fi
      t_mm=$(( 10#$w_mm - e )); t_hh="$w_hh"
      while (( t_mm < 0 ));  do t_mm=$(( t_mm + 60 )); t_hh=$(( (t_hh + 23) % 24 )); done
      while (( t_mm > 59 )); do t_mm=$(( t_mm - 60 )); t_hh=$(( (t_hh + 1) % 24 )); done
    done
    (( ok )) || warn "START not verified inside A's window — saving anyway"
    sleep 1
    save_edit_sheet || warn "overlap edit save did not land (B may not overlap)"
    if "$SCRIPT_DIR/offline.sh" off; then OFFLINE_ON=0; else warn "airplane mode OFF failed"; fi
    return 0
  }

  overlap_edit_once || true
  # the drain rejects the queued PATCH (overlap with A) -> B FAILED. P1-3 also
  # surfaces the rejection PROACTIVELY as the failure dialog over the dashboard
  # (which hides the badge from a dump) — either observation proves it.
  await_failed_badge() {
    # 5 x ~15 s: the offline drain's first attempt can hit NetworkDown (radios
    # still reconnecting) and WorkManager's retry backoff stretches past 30 s.
    local i xmlF
    for i in 1 2 3 4 5; do
      sleep 10
      restart_app || true
      xmlF="$(dump_with_retry 2>/dev/null || true)"
      if [[ -n "$xmlF" ]] && { bounds_of "$xmlF" "dashboard/badge_failed" >/dev/null 2>&1 || \
           bounds_of "$xmlF" "edit/failure_dialog" >/dev/null 2>&1 || \
           printf '%s' "$xmlF" | grep -q "overlaps\|intersects\|another entry"; }; then
        return 0
      fi
    done
    return 1
  }
  local failed=0
  if await_failed_badge; then
    failed=1
  elif [[ "$(b_overlaps_a_on_server)" != "OVERLAP" ]]; then
    # Host-side truth: no B/A window intersection on the server — the edit never
    # landed as an overlap (dial drag miss, or the save was never queued). One
    # bounded full retry of the offline edit, then decide.
    warn "B does not overlap A on the server — retrying the whole offline edit once"
    scroll_top
    overlap_edit_once || true
    if await_failed_badge; then failed=1; fi
  fi
  if (( failed )); then
    sleep 1
    shot 05-sync-states-04-failed-badge
    if bounds_of "$(dump_with_retry 2>/dev/null || true)" "edit/failure_dialog" >/dev/null 2>&1; then
      shot 05-sync-states-05-failure-dialog
    elif tap_tag "dashboard/badge_failed" && wait_for_tag "edit/failure_dialog" 10 2 >/dev/null; then
      shot 05-sync-states-05-failure-dialog
    else
      warn "failure dialog not captured"
    fi
  else
    warn "FAILED badge never appeared (overlap PATCH not rejected?)"
    assert_chk 0 "overlap -> FAILED badge (not observed)"
  fi

  step "05: resolve — edit B back to non-overlap, retry, synced"
  local resolved=0 waited xmlC sb
  # If the failure dialog is open: Edit opens the sheet with values intact.
  xmlC="$(dump_with_retry 2>/dev/null || true)"
  if [[ -n "$xmlC" ]] && bounds_of "$xmlC" "edit/failure_dialog" >/dev/null; then
    tap_row_text "Edit" || true
    sleep 1
  fi
  # Non-overlapping again = one minute LATER than B's original start (the
  # minute drag on the dial can land ±1; +1 pushes strictly away from A, so a
  # near-miss still resolves). B happened minutes ago, so this stays past.
  resolve_start() {
    local rh="${B_START_HH:-}" rm="${B_START_MM:-}"
    [[ "$rh" =~ ^[0-9]+$ ]] || { warn "no remembered START for B"; return 1; }
    rm=$(( 10#$rm + 1 )); if (( rm >= 60 )); then rm=0; rh=$(( (10#$rh + 1) % 24 )); fi
    pick_time "edit/sheet/time_start" "$rh" "$rm"
  }
  # save_edit_sheet (defined above) is reused here: same verify-and-retry save.
  if bounds_of "$(dump_with_retry 2>/dev/null || true)" "edit/sheet" >/dev/null; then
    expand_sheet
    resolve_start || warn "start picker (resolve) failed"
    sleep 1
    save_edit_sheet || true
  else
    # dialog already gone: find B and edit it from the row
    if find_row_text "QA-capture-B" && wait_for_tag "edit/sheet" 10 2 >/dev/null; then
      expand_sheet
      resolve_start || warn "start picker (resolve) failed"
      sleep 1
      save_edit_sheet || true
    fi
  fi
  # the corrected edit syncs; T1 hides the badge change, so verify + re-render
  for waited in 1 2 3 4 5 6; do
    sleep 5
    restart_app || true
    if ! bounds_of "$(dump_with_retry 2>/dev/null || true)" "dashboard/badge_failed" >/dev/null 2>&1; then resolved=1; break; fi
  done
  assert_chk "$resolved" "FAILED entry resolved via edit-to-non-overlap + retry (badge cleared)"
  sleep 1
  shot 05-sync-states-06-resolved-synced
}

# ------------------------------------------------------ group 06: widgets ---

widget_lp_bounds() {  # echo "x y" of a long-press point inside the first widget matching any pattern
  local xml pat b
  xml="$(dump_with_retry)" || return 1
  for pat in "$@"; do
    if b="$(bounds_by_text "$xml" "$pat")"; then
      read -r x1 y1 x2 y2 <<< "$b"
      echo "$(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))"
      return 0
    fi
  done
  return 1
}

open_widget_picker() {  # long-press empty wallpaper -> Widgets; 0 on success
  local pts=(
    "$(( SCREEN_W / 2 )) $(( SCREEN_H * 8 / 100 ))"
    "$(( SCREEN_W * 12 / 100 )) $(( SCREEN_H * 32 / 100 ))"
    "$(( SCREEN_W * 88 / 100 )) $(( SCREEN_H * 32 / 100 ))"
    "$(( SCREEN_W / 2 )) $(( SCREEN_H * 50 / 100 ))"
    "$(( SCREEN_W * 25 / 100 )) $(( SCREEN_H * 62 / 100 ))"
  )
  local p x y
  for p in "${pts[@]}"; do
    read -r x y <<< "$p"
    longpress_at "$x" "$y"
    if wait_text "Widgets" 5; then return 0; fi
    ash input keyevent 4   # some other menu (icon/widget) — back out, try next spot
    sleep 1
  done
  return 1
}

g06_widgets() {
  step "Group 06 — widgets"
  press_home
  wait_launcher 15 || true
  sleep 2
  shot 06-widgets-01-home-both-widgets

  step "06: widget picker previews (long-press wallpaper -> Widgets)"
  if open_widget_picker; then
    tap_row_text "Widgets" || true
    sleep 2
    # scroll the picker until the Baby Buddy Next section shows, then tap it
    local found=0 i
    for i in 1 2 3 4 5 6; do
      if tap_row_text "Baby Buddy Next"; then found=1; break; fi
      ash input swipe "$(( SCREEN_W / 2 ))" "$(( SCREEN_H * 72 / 100 ))" "$(( SCREEN_W / 2 ))" "$(( SCREEN_H * 30 / 100 ))" 300
      sleep 2
    done
    if (( found )); then
      sleep 2
      shot 06-widgets-02-picker-previews
    else
      warn "Baby Buddy Next section not found in the widget picker"
    fi
    ash input keyevent 4
    sleep 1
    ash input keyevent 4
    sleep 1
  else
    warn "launcher 'Widgets' menu never appeared — picker previews skipped"
  fi

  step "06: sleep running on the widget"
  # No waits on the ticking-timer screen (dumps get flaky): tap, go home, shoot.
  if open_app && tap_tag "dashboard/quick_action/SLEEP"; then
    sleep 3
    press_home
    wait_launcher 15 || true
    sleep 3   # widget refresh lands within a beat
    shot 06-widgets-03-home-sleep-running
    open_app || true
    discard_timer "SLEEP"
  else
    warn "could not start sleep for the widget running state"
  fi
  wait_launcher 5 || true

  step "06: config screens via long-press -> Configure"
  local spec pat name lp x y
  for spec in "Bottle|06-widgets-04-quickactions-config" "Last feed|06-widgets-05-caredashboard-config"; do
    pat="${spec%%|*}"; name="${spec#*|}"
    if lp="$(widget_lp_bounds "$pat")"; then
      read -r x y <<< "$lp"
      longpress_at "$x" "$y"
      if wait_text "Configure" 6; then
        tap_row_text "Configure" || true
        if wait_focus_pkg 10; then
          sleep 2
          shot "$name"
          ash input keyevent 4
          sleep 2
        else
          warn "config activity did not open for $pat"
          ash input keyevent 4
        fi
      else
        warn "no Configure entry for widget '$pat' (placement may not expose one)"
        ash input keyevent 4
      fi
      wait_launcher 8 || true
    else
      warn "widget matching '$pat' not found on the home screen (placed?)"
    fi
  done
}

# -------------------------------------------------------- group 07: help ---

g07_help() {
  step "Group 07 — help slideshow + about"
  open_app || return 1
  scroll_top

  if tap_tag "dashboard/overflow"; then
    local xml
    xml="$(dump_with_retry)" || return 1
    if tap_text "$xml" "Help"; then
      local pg
      for pg in 1 2 3 4; do
        sleep 1
        shot "07-help-0${pg}-page${pg}"
        if (( pg < 4 )); then
          tap_row_text "Next" || break
        else
          tap_row_text "Done" || true
        fi
      done
    else
      warn "Help menu item missing"
      ash input keyevent 4
    fi
    sleep 1
    if open_app && tap_tag "dashboard/overflow"; then
      xml="$(dump_with_retry)" || true
      if tap_text "$xml" "About"; then
        sleep 1
        shot 07-help-05-about
        ash input keyevent 4   # dismiss the About dialog
        sleep 1
      else
        warn "About menu item missing"
        ash input keyevent 4
      fi
    else
      warn "dashboard not reachable for About"
    fi
  else
    warn "dashboard/overflow not found"
  fi
}

# --------------------------------------------------------------- main -------

check_env

# g01's sign-out → UI-login dance is the run's most stateful sequence (sign-out
# clears the DB mid-flight); one clean retry on failure resynchronized every
# observed flake.
g01_login || { warn "g01 failed — one full retry after a fresh login"
  ash am force-stop "$PKG" 2>/dev/null || true
  bash "$SCRIPT_DIR/fresh-install-login.sh" >/dev/null 2>&1 || true
  g01_login; }
g02_dashboard
g03_quickactions
g04_edit
g05_sync_states
g06_widgets
g07_help

api_cleanup

# final state: fresh dashboard, Milo active, refreshed timeline
open_app || true
restart_app || true
ensure_milo
shot 99-final-dashboard

step "Captured-state inventory"
for d in 01-login 02-dashboard 03-quickactions 04-edit 05-sync-states 06-widgets 07-help; do
  n="$(find "$SHOTDIR" -maxdepth 1 -name "${d}-*.png" | wc -l | tr -d ' ')"
  printf '  %-18s %s\n' "$d" "$n"
done
printf '  %-18s %s\n' "TOTAL" "$(find "$SHOTDIR" -name '*.png' | wc -l | tr -d ' ')"

if (( ASSERT )); then
  if (( FAILURES > 0 )); then
    printf '\n--assert result: %s FAILURE(S)\n' "$FAILURES"
  else
    printf '\n--assert result: ALL CANARIES PASS\n'
  fi
fi
# exit code is finalized by the EXIT trap (restore_state)
