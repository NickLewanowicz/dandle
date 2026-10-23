---
title: "Flow verification: Widgets"
---

# 50 — Widgets (spec §7, checklists 9–10) — QA flow doc

Implementation reference: `docs/flows/new-app/widgets.md`. Engineer's placement/config evidence: `docs/screenshots/new-app/widgets/`.

**Purpose:** re-verify the engineer's widget claims independently: placement + live data, tap semantics (trampoline vs deep-link), refresh triggers, and configuration.


## v0.4 — owner UI rework (U10)

- **Quick Actions tiles whisper the last activity**: an idle tile shows a subdued second line under its label — compact since-text `45m` / `1h30m` / `2d` (newest event of that type for the widget's child; none when there is no history). `WidgetStateMapper.compactSince` + `QuickActionSlot.lastText`, pinned by `WidgetStateMapperTest`.
- **Running tiles light up in their semantic type color** (not the generic accent) with the elapsed counter replacing the label; the last-line is hidden while running. Dark/light pairs from `TypeColors`.
- "Opens app on the timers strip" below now reads: opens the app with the running-timer **card** below the buttons (the strip no longer exists); a running tile's tap goes straight to the stop-guard dialog (T5, unchanged).

Screenshots: `06-widgets-01-home-both-widgets` (idle tiles with last-lines), `06-widgets-03-home-sleep-running` (sleep tile lit in indigo with elapsed).

## v0.5 — one-tap starts + never-stale freshness

- **One-tap timer starts for every choice-free timer type**: Sleep, Pump, and Tummy tiles all start their timer via a background broadcast (`WidgetTimerStartAction`, keyed by type — replaces the sleep-only `WidgetSleepStartAction`) with no app launch. Bottle/Diaper/Note keep deep-linking into the app with the panel pre-opened (they need choices). RUNNING tiles keep the T5 contract unchanged: tap → stop-guard deep link.
- **The care widget shows EVERY running timer**: sleep + pumping + tummy time can run simultaneously; each gets its own accented "… since HH:MM · Xm" row at the top, longest-running first (`CareDashboardState.runningSince` is a list — it used to render only the first timer). Cross-child awareness is now one quiet line per other-child timer (`crossChildTimers`), also longest-running first.
- **Freshness contract (spec §7.2)**: every local state change refreshes both widgets immediately — timer start (in-app AND widget broadcast), timer stop/save/discard, entry create/edit/delete, child switch, sign-out, pull-to-refresh timer reconciliation, outbox drain completion. While any timer runs, a self-chaining ~60s WorkManager one-time tick (`WidgetTickerWorker`, armed/cancelled by `WidgetRefresher` from the running-timer Room state) keeps elapsed counters within a minute; the 15-min periodic job remains the safety net. Render is Room-only — never network-bound.
- Pinned by `WidgetStateMapperTest` (multi-timer rows, plural cross-child lines, since/elapsed shapes).

Screenshots: `06-widgets-01…03` (v0.4 set) + new v0.5 evidence in `/tmp/widget-qa/` at verification time (one-tap start, running row live tick, post-stop clear).

## How to verify

- The two Glance widgets live on the launcher — drive with `adb shell uiautomator dump` + `input tap` (cells expose text/content-desc: `Sleep`, `Bottle`, `Diaper`, `Pump`, `Last feed`, …, `Asleep since 13:00 · 12m elapsed` for the running row — which renders FIRST when a timer is active); no in-app tags involved.
- Foreground checks: `adb shell dumpsys activity activities | grep topResumedActivity` (launcher must stay resumed for trampoline taps).
- Server: `scripts/qa/server-log.sh` must show `POST /api/timers/` for sleep-start taps and **no** requests for navigation taps; `scripts/qa/api.sh GET /api/timers/` for state.
- Refresh triggers: in-app save (`BB/SYNC drain … Completed` → both widgets re-read Room), outbox drain completion, trampoline actions, timer start/stop from the app or the widget, entry edit/delete, child switch. Live tick: with a timer running, `adb shell dumpsys jobscheduler | grep -i widget` shows the pending `widget-live-tick` one-time job re-armed every ~60s (absent when no timer runs).
- v0.5 one-tap starts: idle Pump/Tummy taps must behave like the sleep tap (below) — `POST /api/timers/` with `Pumping-BBapp:4` / `Tummy time-BBapp:3` and the launcher staying resumed. Care widget: start sleep AND pump → TWO accented rows; stop one → only the other remains, immediately.

## Observed

- **Persistence:** both widgets survived `pm clear` of the app (launcher kept them) and re-populated with live data on the next app refresh; after re-bind they enter resize mode once — tap empty space to commit (matches `widgets.md` launcher note).
- **CareDashboard rows** (`4×2`): `Last feed 1h · 120 mL`, `Last diaper 19m · Wet`, `Last nap 2m · 5m`, `Last pump 2m · 0 mL` — minute-granular, correct "Xm · detail" shapes ✓. Empty-state rule (omitted rows) not exercised (data always present).
- **Running row:** renders FIRST, accented ("Asleep since 13:00 · 12m elapsed" shape — persona round 2 wording) when a timer starts, and clears after the timer is saved ✓.
- **In-app write refresh:** logging a feeding in the app updated "Last feed 0m" on the home-screen widget without any manual refresh ✓ (checklist 10.2).
- **Drain-completion refresh:** entries created offline (diaper 17 m, nap 5 m) appeared in the widget immediately after the replay drain ✓ (checklist 10.3, adapted: single device, offline flavor).
- **QuickActions sleep cell, idle tap:** NO app launch (topResumedActivity stayed `NexusLauncherActivity`), server timer created (`POST /api/timers/` 201) ✓ (checklist 9.3).
- **QuickActions sleep cell, running tap:** app opens on the timers strip ✓ (checklist 9.4).
- **Diaper cell tap:** app opens with the Diaper panel pre-opened (`dashboard/panel/wet|solid|…` present) ✓ (checklist 9.5).
- **CareDashboard nap-row tap:** timer starts (`POST /api/timers/` 201) **but the app comes to the foreground** — reproduced twice cleanly (launcher resumed → tap → `MainActivity` resumed). The QuickActions path (broadcast) correctly stays on the launcher; the dashboard nap row uses an activity trampoline that lands on the app task. **P2 — finding #5.** **[Resolved by design, persona round 2, 2026-10-18: info rows opening the app is now the specified behavior — timers are never started from the widget.]**
- **Configure:** long-press on a placed widget goes straight to launcher resize mode; **no Configure entry** — `android:reconfigurable` is not declared in either `*widget_info.xml` (only `android:configure`), so reconfiguration is unreachable after placement (remove + re-add only). Add-time config screens verified by the engineer (`widgets/quick_actions_config.png`, `widgets/care_dashboard_config.png`). **P3 — finding #7.**

**Watch it happen — the widget tap contract.**

![Widgets: tap the QuickActions Sleep cell — the timer starts via broadcast and the launcher stays foreground (no app launch) while both widgets flip to a running state, the care widget gaining a first "Asleep since HH:MM · Xm" row — then the care widget's Last feed row opens the app, which auto-opens the running timer's card.](../../media/demo-widgets.gif)

*The idle Sleep-cell tap starts the timer (`POST /api/timers/` 201) without leaving the launcher; both widgets flip to running — the care widget's first row reads "Asleep since HH:MM · Xm" — then the Last feed row opens the app straight onto the running timer's card.*

## Branches / states

| Branch | Result |
|---|---|
| Idle sleep/pump/tummy tap | Trampoline timer, no launch ✓ (v0.5: all three; sleep re-verified, pump/tummy same broadcast path) |
| Running tile tap | Opens app into the stop guard ✓ |
| Diaper/feed/note tap | Deep-link with panel pre-opened ✓ |
| Nap-row tap | Timer starts, **but app launches (P2)** — resolved by design: the row now just opens the app, no timer started |
| Timers running | CareDashboard running row appears/clears ✓; v0.5: one row per simultaneous timer |
| Timer running, widget idle ~60s | Elapsed text ticks within a minute (v0.5 `WidgetTickerWorker`); chain cancels on stop |
| Long-press → Configure | Not offered (P3); resize only |

## Screenshots

`docs/screenshots/new-app/flows/06-widgets-01…03` — live widgets, running state, deep-link, nap-tap result, long-press resize, widget picker (preview copy "Four tappable care buttons…", "Last feed, diaper, nap, and pump at a glance"), config attempts. Engineer's: `docs/screenshots/new-app/widgets/*` (widget close-ups).

## Deviations from spec

- **P2** — finding #5 (nap-row trampoline launches app; spec §7.2 "nap row starts a sleep timer" + widgets.md "without opening the app") — **resolved by design**: info rows opening the app is now the specified behavior, timers are never started from the widget (persona round 2, 2026-10-18).
- **P3** — finding #7 (spec §7.3 long-press → Configure unreachable on placed widgets).
- QuickActions cell running-state accent: in the resized 4×1 icon-only variant no visible accent/elapsed change could be confirmed after the trampoline start (CareDashboard running row did update). Not separately filed — needs a check on the default 2×2 size.
