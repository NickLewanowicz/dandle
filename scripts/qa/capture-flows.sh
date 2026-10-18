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
#      "Wet (Green)" headline within 5 s — WITHOUT restart or refresh.
#   A2 edit-update: after a -15m stepper nudge + Save, the timeline must show
#      the nudged HH:mm within 5 s.
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
    if verify_typed "$got" "$value"; then
      return 0
    fi
    printf 'WARN: %s = "%s" (wanted "%s") — retrying\n' "$tag" "$got" "$value" >&2
    hide_kb
  done
  warn "typing into $tag did not verify"
  return 1
}

hide_kb() {  # dismiss the IME and VERIFY it is gone. A tap issued while the
             # keyboard window is up is consumed by the IME, not the app — and
             # the IME show is ASYNC: right after typing, mInputShown can still
             # be false while the keyboard animates in, so a single "false"
             # check is not trusted (re-checked after a beat).
  local i=0
  while :; do
    if ash dumpsys input_method 2>/dev/null | tr -d '\r' | grep -q 'mInputShown=true'; then
      ash input keyevent 4
      sleep 2
      i=$((i + 1))
      (( i >= 4 )) && { warn "keyboard would not dismiss"; return 1; }
    else
      sleep 1
      if ash dumpsys input_method 2>/dev/null | tr -d '\r' | grep -q 'mInputShown=true'; then
        continue   # it popped up after all - dismiss on the next pass
      fi
      sleep 1
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
  local tag="$1" i b y2 xml
  for i in 1 2 3 4 5 6; do
    if xml="$(dump_with_retry 2>/dev/null)"; then
      if b="$(bounds_of "$xml" "$tag")"; then
        y2="$(printf '%s' "$b" | awk '{print $4}')"
        (( y2 <= SCREEN_H - 120 )) && return 0
      fi
      ash input swipe "$(( SCREEN_W / 2 ))" "$(( SCREEN_H * 72 / 100 ))" "$(( SCREEN_W / 2 ))" "$(( SCREEN_H * 32 / 100 ))" 250
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
vtag()  { ensure_visible "$1" && tap_tag "$1"; }
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
    if [[ "$type" == "CHANGE" || "$type" == "NOTE" ]]; then
      vtag "dashboard/panel/save" || { warn "panel/save unreachable for $type"; return 1; }
    else
      if ! tap_tag "dashboard/timer/$type/save" 2>/dev/null; then
        vtag "dashboard/panel/save" || { warn "no save path for $type"; return 1; }
      fi
    fi
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
  wait_for_tag "dashboard" 30 2 >/dev/null || { warn "dashboard not visible after restart"; return 1; }
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
discard_timer() {
  local type="$1" xml
  if tap_tag "dashboard/timer/$type/discard" 2>/dev/null; then
    sleep 2
    # the strip discard runs the stop guard: >10 s opens the stop dialog
    xml="$(dump_with_retry 2>/dev/null)" || return 0
    if bounds_of "$xml" "dashboard/stop_dialog" >/dev/null; then
      tap_tag "dashboard/stop_dialog/discard" || true
      sleep 1
    fi
    return 0
  fi
  tap_tag "dashboard/timer/$type" || { warn "no timer card for $type"; return 0; }
  sleep 2
  xml="$(dump_with_retry)" || return 1
  if bounds_of "$xml" "dashboard/stop_dialog" >/dev/null; then
    tap_tag "dashboard/stop_dialog/discard" || true
  fi
  wait_gone_tag "dashboard/stop_dialog" 8 || warn "stop dialog still open after discard ($type)"
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
    tap_tag "dashboard/overflow" || { warn "overflow missing"; return 1; }
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
  if tap_tag "dashboard/quick_action/FEEDING" && wait_for_tag "dashboard/panel/FEEDING" 15 2 >/dev/null; then
    shot 03-quickactions-01-feeding-panel
    vtag "dashboard/panel/feeding_type/formula" && sleep 1
    shot 03-quickactions-02-feeding-cascade   # methods drop to bottle/parent fed/self fed
    vtag "dashboard/panel/feeding_type/breast milk" || true
    vtag "dashboard/panel/feeding_method/bottle" || true
    vtype "dashboard/panel/amount" "149" && hide_kb
    save_panel "FEEDING" || true
    wait_gone_tag "dashboard/panel/FEEDING" 15 || warn "feeding panel did not close after save"
  else
    warn "FEEDING quick action failed"
  fi
  scroll_top

  step "03: DIAPER panel + extra options"
  if tap_tag "dashboard/quick_action/CHANGE" && wait_for_tag "dashboard/panel/CHANGE" 15 2 >/dev/null; then
    shot 03-quickactions-03-diaper-panel   # Save disabled: Wet/Solid both off
    vtag "dashboard/panel/wet" || true
    vtag "dashboard/panel/extra_options" && sleep 1
    shot 03-quickactions-04-diaper-extra-options   # color chips + amount
    vtag "dashboard/panel/color/green" || true
    vtype "dashboard/panel/amount" "73" && hide_kb
    scroll_top   # collapse is at the top of the panel; bring it back
    vtag "dashboard/panel/extra_options" || true   # collapse again so Save is reachable
    save_panel "CHANGE" || true
    wait_gone_tag "dashboard/panel/CHANGE" 15 || warn "diaper panel did not close after save"

    # --- CANARY A1: save must be visible on the timeline within 12 s, no restart,
    # no refresh. "Wet (Green)" is our diaper's unique headline (color set).
    # A FAILED dump (UiAutomation transiently busy: insert animation, drain, chip
    # settle) does NOT count against the app — only a successful dump missing the
    # row proves absence. Observed on device: the row renders within ~1 s.
    local a1=0 tries xmlA
    scroll_top   # the (possibly still-open/tall) panel pushes the timeline below the fold
    for tries in 1 2 3 4 5 6 7 8 9 10 11 12; do
      if xmlA="$(dump_with_retry 2>/dev/null)"; then
        if printf '%s' "$xmlA" | grep -q 'text="Today"' \
           && printf '%s' "$xmlA" | grep -q 'text="Wet (Green)"'; then a1=1; break; fi
      fi
      sleep 1
    done
    assert_chk "$a1" "A1 save-visibility: our 'Wet (Green)' row under Today within 12 s of saving a diaper (no restart/refresh)"
  else
    warn "DIAPER quick action failed"
    assert_chk 0 "A1 save-visibility (diaper save step failed — canary not exercised)"
  fi
  scroll_top

  step "03: duplicate guard (diaper saved seconds ago -> save again)"
  if tap_tag "dashboard/quick_action/CHANGE" && wait_for_tag "dashboard/panel/CHANGE" 15 2 >/dev/null; then
    vtag "dashboard/panel/wet" || true
    vtag "dashboard/panel/save" || true   # pops the duplicate dialog - captured next
    if wait_for_tag "dashboard/duplicate_dialog" 10 2 >/dev/null; then
      shot 03-quickactions-05-duplicate-guard
      ash input keyevent 4   # dismiss WITHOUT adding (no cleanup needed)
      sleep 1
    else
      warn "duplicate dialog did not appear (was the last diaper <4 min ago?)"
    fi
    tap_tag "dashboard/panel/close" || ash input keyevent 4
  else
    warn "duplicate-guard diaper panel failed"
  fi
  scroll_top

  step "03: SLEEP running panel, then >10s stop-guard dialog"
  if tap_tag "dashboard/quick_action/SLEEP" && wait_for_tag "dashboard/panel/SLEEP" 15 2 >/dev/null; then
    shot 03-quickactions-06-sleep-running   # live counter visible
    sleep 12                                # must exceed 10 s for the stop guard
    if tap_tag "dashboard/timer/SLEEP" || { scroll_top; tap_tag "dashboard/timer/SLEEP"; }; then
      if wait_for_tag "dashboard/stop_dialog" 10 2 >/dev/null; then
        shot 03-quickactions-07-sleep-stopguard   # Save emphasized
        tap_tag "dashboard/stop_dialog/discard" || true
      else
        warn "stop dialog did not appear for sleep"
      fi
    else
      warn "could not tap the running sleep timer card"
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
  if tap_tag "dashboard/quick_action/PUMPING" && wait_for_tag "dashboard/panel/PUMPING" 15 2 >/dev/null; then
    shot 03-quickactions-09-pumping-panel
    discard_timer "PUMPING"
  else
    warn "PUMPING quick action failed"
  fi
  scroll_top

  step "03: TUMMY TIME panel (discarded <10s, silently)"
  if tap_tag "dashboard/quick_action/TUMMY_TIME" && wait_for_tag "dashboard/panel/TUMMY_TIME" 15 2 >/dev/null; then
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

  step "04: feeding sheet + -15m nudge"
  if tap_row_text "Bottle · 149 mL" && wait_for_tag "edit/sheet" 10 2 >/dev/null; then
    expand_sheet
    local xml before after new_hm changed
    xml="$(dump_with_retry)" || return 1
    # The sheet is a Compose DIALOG window: test tags inside it never reach
    # uiautomator (QA finding #8), so read the START value by its day-prefixed
    # text ("Today 18:53") — unique to the sheet (timeline rows show bare HH:mm).
    sheet_start_value() {
      printf '%s' "$1" | grep -oE 'text="[^"]+ [0-9]{2}:[0-9]{2}"' | head -1 | sed -E 's/^text="([^"]*)".*/\1/'
    }
    before="$(sheet_start_value "$xml")"
    shot 04-edit-01-feeding-sheet   # START + END stepper rows visible
    # Tag invisible in dialog windows (QA finding #8): tap the visible "-15m" text.
    tap_row_text "−15m" || tap_row_text "-15m" || warn "start_minus stepper missing"
    sleep 1
    xml="$(dump_with_retry)" || return 1
    after="$(sheet_start_value "$xml")"
    shot 04-edit-02-feeding-nudged   # value visibly changed
    if [[ -n "$before" && -n "$after" && "$before" != "$after" ]]; then changed=1; else changed=0; fi
    assert_chk "$changed" "A2a edit sheet value visibly changed after -15m ($before -> $after)"
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
  if tap_row_text "Wet (Green)" && wait_for_tag "edit/sheet" 10 2 >/dev/null; then
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
    warn "diaper row 'Wet (Green)' not found (was group 03 skipped?)"
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

sleep_with_note() {  # start a sleep timer with a marker note, run >10s, save
                     # via the strip Save (same draft; accepts the duplicate
                     # guard between the two sleeps)
  tap_tag "dashboard/quick_action/SLEEP" || return 1
  wait_for_tag "dashboard/panel/SLEEP" 15 2 >/dev/null || return 1
  vtype "dashboard/panel/note" "$1" && hide_kb
  sleep 12                              # >10 s so the stop guard applies
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
  # A and B are created ONLINE (both sync). Then, OFFLINE, A's END is nudged
  # +15m so it swallows B's window (B is scripted ~60-90 s after A's stop, so
  # a +1m nudge no longer reaches it); the queued PATCH is rejected at replay
  # (overlap) and A ends FAILED — resolution path captured below.
  # One dump flake must not sink the whole overlap scenario: retry each creation.
  if sleep_with_note "QA-capture-A"; then printf '    sleep A synced\n'
  else scroll_top; sleep 2
       if sleep_with_note "QA-capture-A"; then printf '    sleep A synced (retry)\n'; else warn "sleep A failed"; fi
  fi
  if sleep_with_note "QA-capture-B"; then printf '    sleep B synced\n'
  else scroll_top; sleep 2
       if sleep_with_note "QA-capture-B"; then printf '    sleep B synced (retry)\n'; else warn "sleep B failed"; fi
  fi
  restart_app || true   # rows visible
  scroll_top
  if "$SCRIPT_DIR/offline.sh" on; then OFFLINE_ON=1; else warn "airplane mode ON failed"; fi
  sleep 20   # ensure A.end (+1m target) stays in the past when the edit saves
  if tap_row_text "QA-capture-A" && wait_for_tag "edit/sheet" 10 2 >/dev/null; then
    expand_sheet
    # Dialog-window tags are invisible to uiautomator (QA finding #8): the END
    # row's "+1m" is the SECOND "+1m" on the sheet (START row has the first).
    local nudge_ok=0 ri
    for ri in 1 2 3 4 5; do
      if tap_row_text_nth "+15m" 2; then nudge_ok=1; break; fi
      sleep 2   # the swipe-scroll settle can transiently hide the row from the dump
    done
    (( nudge_ok )) || warn "end +15m stepper missing (after retries)"
    sleep 1
    tap_row_text "Save" || true
    wait_gone_tag "edit/sheet" 10 || true
  else
    warn "could not edit sleep A (row text missing?)"
  fi
  if "$SCRIPT_DIR/offline.sh" off; then OFFLINE_ON=0; else warn "airplane mode OFF failed"; fi
  # the drain rejects the queued PATCH (overlap with B) -> A FAILED
  local failed=0 i
  for i in 1 2 3; do
    sleep 10
    restart_app || true
    if bounds_of "$(dump_with_retry 2>/dev/null || true)" "dashboard/badge_failed" >/dev/null 2>&1; then failed=1; break; fi
  done
  if (( failed )); then
    sleep 1
    shot 05-sync-states-04-failed-badge
    if tap_tag "dashboard/badge_failed" && wait_for_tag "edit/failure_dialog" 10 2 >/dev/null; then
      shot 05-sync-states-05-failure-dialog
    else
      warn "failure dialog not captured"
    fi
  else
    warn "FAILED badge never appeared (overlap PATCH not rejected?)"
    assert_chk 0 "overlap -> FAILED badge (not observed)"
  fi

  step "05: resolve — edit A back to non-overlap, retry, synced"
  local resolved=0 waited xmlC
  # If the failure dialog is open: Edit opens the sheet with values intact.
  xmlC="$(dump_with_retry 2>/dev/null || true)"
  if [[ -n "$xmlC" ]] && bounds_of "$xmlC" "edit/failure_dialog" >/dev/null; then
    tap_row_text "Edit" || true
    sleep 1
  fi
  if bounds_of "$(dump_with_retry 2>/dev/null || true)" "edit/sheet" >/dev/null; then
    vtag "edit/stepper/end-1_minus" || warn "end -1m stepper missing"
    sleep 1
    vtag "edit/save" || true
    wait_gone_tag "edit/sheet" 10 || true
  else
    # dialog already gone: find A and edit it from the row
    if tap_row_text "QA-capture-A" && wait_for_tag "edit/sheet" 10 2 >/dev/null; then
    expand_sheet
      vtag "edit/stepper/end-1_minus" || warn "end -1m stepper missing"
      sleep 1
      vtag "edit/save" || true
      wait_gone_tag "edit/sheet" 10 || true
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

g01_login
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
