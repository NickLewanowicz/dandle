---
title: "Original app: Discrepancies"
---

# 99 — Discrepancies: live app v2.6.4 vs docs/research/existing-app-architecture.md

The research doc is largely accurate. Everything below is where the live black-box session
differed from it, or where the doc was ambiguous. Order: functional differences first.

## Functional differences

1. **Feeding methods for "Solid food" include Bottle.**
   Doc §4.4: solid food → (Parent fed, Self fed). Live app: `BOTTLE, PARENT FED, SELF FED`
   (screenshot 04-feeding/08). Formula and Fortified breast milk also offer the same three.
   Only "Breast milk" gets the six-method set.

2. **Help slideshow has NO "Home Assistant Ingress" slide.**
   Doc §5 lists 4 sections under "Logging in" (Login, QR Code login, Insecure Login, Home
   Assistant Ingress). Live app: 3 sections only. Total slideshow = 5 topics / 9 slides.

3. **Timer-name slot allocation is per-activity fixed, doc reads like "lowest free 1..4".**
   Observed creates: `Feeding-BBapp:1`, `Sleep-BBapp:2` — consistent with feeding=1, sleep=2,
   tummy time=3, pumping=4 as fixed slots. No collision handling was ever observable; the doc's
   regex/restart legacy handling (`PATCH api/timers/{id}/restart/`) was not observable against a
   v2 server (timers are deleted, never left inactive), matching the doc's own note.

4. **Insecure-connection dialog wording differs from the in-app help image (stale help asset).**
   Live dialog: "This server URL does not start with 'https'. This means that the password you
   entered can be intercepted and stolen!"
   Help image (slide "Insecure Login"): "You have entered a URL  that does not start with
   'https'. …" — the bundled help screenshots predate the current build (also true for the
   save-button position shown in the "Using Timers" slide 3: image shows it bottom-LEFT, live
   app places it bottom-RIGHT).

5. **"Login failed" dialog prefixes the server alert with ": ".**
   Cosmetic bug: wrong password shows ": Please enter a correct username and password. …" — the
   alert-pill parsing yields an empty label before the colon. Research doc mentioned server
   alert parsing but not the artifact.

6. **Help navigation: the on-screen arrows did not respond to taps** in this environment; only
   swipe gestures advanced the help pagers. Possibly an emulation artifact, but the new app
   should make its help controls tap-reliable.

7. **Help/QR tutorial bubble re-appears after logout** — "shown a limited number of times"
   (doc §5) — observed at least 3 showings (initial + after each logout); counters seem per-
   login-session or generous. Exact quota unverified.

## Confirmations where reality was checked and matched the doc
- Six quick actions, order, icons, per-child ViewPager, inline panels.
- Server timer model: create `"<Type>-BBapp:<n>"`, save = POST entry + DELETE timer, >10 s
  "Stop timer?" KEEP/STOP dialog, ≤10 s silent cancel.
- Diaper: no save until a toggle; color/amount under "+" expander; `POST api/changes/` with
  single `time`, `wet`, `solid`, `color`, `amount`.
- Tummy time note → `milestone` field. Pumping amount required (default 0.0, not nullable)
  while feeding amount is nullable ("None").
- Long-press → `{server}/{uiPath}/{id}/` (Chrome hit `/login/?next=/sleep/6/`); delete → confirm
  dialog → `DELETE api/<type>/<id>/`.
- Settings = single Dark/Light mode pref; menu = Help/Settings/Logout/Contact developer/About
  (no debug-log entry in release build).
- Timeline: merged reverse-chron, no day grouping, `(H:MM)` durations, "Amount: N" lines, color
  swatches, method-specific feeding icons, new entries appear at top after save.
- Timer continuation across process death and backgrounding with server-authoritative elapsed
  time.

## Tooling/environment notes (not app bugs, but affect replication testing)
- **Live counter keeps uiautomator non-idle**: with any timer panel open, `uiautomator dump`
  fails ("could not get idle state") or returns a stale tree; the MCP `ui_describe` likewise
  returns stale elements. Panel interactions must be driven from screenshots. Plan for
  test-idle-friendly implementations (e.g. `importantForAccessibility` static counter text or
  slower cadence) in the new app if UI automation matters.
- The shared emulator also hosts `app.dandle` (the new template app) and
  `com.openbaby.monitor`; the former was installed/launched mid-session by another process and
  twice stole the foreground (see 03-sleep/04-interrupted-by-other-app.png).
- Seed data contains future-dated entries (e.g. note at 22:54Z while "now" was ~21:0xZ); the app
  displays them faithfully — do not mistake this for a timezone bug.
