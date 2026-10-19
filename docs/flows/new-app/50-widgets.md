# 50 — Widgets (spec §7, checklists 9–10) — QA flow doc

Implementation reference: `docs/flows/new-app/widgets.md`. Engineer's placement/config evidence: `docs/screenshots/new-app/widgets/`.

**Purpose:** re-verify the engineer's widget claims independently: placement + live data, tap semantics (trampoline vs deep-link), refresh triggers, and configuration.


## v0.4 — owner UI rework (U10)

- **Quick Actions tiles whisper the last activity**: an idle tile shows a subdued second line under its label — compact since-text `45m` / `1h30m` / `2d` (newest event of that type for the widget's child; none when there is no history). `WidgetStateMapper.compactSince` + `QuickActionSlot.lastText`, pinned by `WidgetStateMapperTest`.
- **Running tiles light up in their semantic type color** (not the generic accent) with the elapsed counter replacing the label; the last-line is hidden while running. Dark/light pairs from `TypeColors`.
- "Opens app on the timers strip" below now reads: opens the app with the running-timer **card** below the buttons (the strip no longer exists); a running tile's tap goes straight to the stop-guard dialog (T5, unchanged).

Screenshots: `06-widgets-01-home-both-widgets` (idle tiles with last-lines), `06-widgets-03-home-sleep-running` (sleep tile lit in indigo with elapsed).

## How to verify

- The two Glance widgets live on the launcher — drive with `adb shell uiautomator dump` + `input tap` (cells expose text/content-desc: `Sleep`, `Bottle`, `Diaper`, `Pump`, `Last feed`, …, `Running: Sleep — 0m — tap to stop`); no in-app tags involved.
- Foreground checks: `adb shell dumpsys activity activities | grep topResumedActivity` (launcher must stay resumed for trampoline taps).
- Server: `scripts/qa/server-log.sh` must show `POST /api/timers/` for sleep-start taps and **no** requests for navigation taps; `scripts/qa/api.sh GET /api/timers/` for state.
- Refresh triggers: in-app save (`BB/SYNC drain … Completed` → both widgets re-read Room), outbox drain completion, trampoline actions.

## Observed

- **Persistence:** both widgets survived `pm clear` of the app (launcher kept them) and re-populated with live data on the next app refresh; after re-bind they enter resize mode once — tap empty space to commit (matches `widgets.md` launcher note).
- **CareDashboard rows** (`4×2`): `Last feed 1h · 120 mL`, `Last diaper 19m · Wet`, `Last nap 2m · 5m`, `Last pump 2m · 0 mL` — minute-granular, correct "Xm · detail" shapes ✓. Empty-state rule (omitted rows) not exercised (data always present).
- **Running row:** appears accented ("Running: Sleep — 0m — tap to stop") when a timer starts, and clears after the timer is saved ✓.
- **In-app write refresh:** logging a feeding in the app updated "Last feed 0m" on the home-screen widget without any manual refresh ✓ (checklist 10.2).
- **Drain-completion refresh:** entries created offline (diaper 17 m, nap 5 m) appeared in the widget immediately after the replay drain ✓ (checklist 10.3, adapted: single device, offline flavor).
- **QuickActions sleep cell, idle tap:** NO app launch (topResumedActivity stayed `NexusLauncherActivity`), server timer created (`POST /api/timers/` 201) ✓ (checklist 9.3).
- **QuickActions sleep cell, running tap:** app opens on the timers strip ✓ (checklist 9.4).
- **Diaper cell tap:** app opens with the Diaper panel pre-opened (`dashboard/panel/wet|solid|…` present) ✓ (checklist 9.5).
- **CareDashboard nap-row tap:** timer starts (`POST /api/timers/` 201) **but the app comes to the foreground** — reproduced twice cleanly (launcher resumed → tap → `MainActivity` resumed). The QuickActions path (broadcast) correctly stays on the launcher; the dashboard nap row uses an activity trampoline that lands on the app task. **P2 — finding #5.**
- **Configure:** long-press on a placed widget goes straight to launcher resize mode; **no Configure entry** — `android:reconfigurable` is not declared in either `*widget_info.xml` (only `android:configure`), so reconfiguration is unreachable after placement (remove + re-add only). Add-time config screens verified by the engineer (`widgets/quick_actions_config.png`, `widgets/care_dashboard_config.png`). **P3 — finding #7.**

## Branches / states

| Branch | Result |
|---|---|
| Idle sleep tap | Trampoline timer, no launch ✓ |
| Running sleep tap | Opens app on strip ✓ |
| Diaper/feed/pump tap | Deep-link with panel pre-opened ✓ |
| Nap-row tap | Timer starts, **but app launches (P2)** |
| Timer running | CareDashboard running row appears/clears ✓ |
| Long-press → Configure | Not offered (P3); resize only |

## Screenshots

`docs/screenshots/new-app/50-widgets/01…13` — live widgets, running state, deep-link, nap-tap result, long-press resize, widget picker (preview copy "Four tappable care buttons…", "Last feed, diaper, nap, and pump at a glance"), config attempts. Engineer's: `docs/screenshots/new-app/widgets/*`.

## Deviations from spec

- **P2** — finding #5 (nap-row trampoline launches app; spec §7.2 "nap row starts a sleep timer" + widgets.md "without opening the app").
- **P3** — finding #7 (spec §7.3 long-press → Configure unreachable on placed widgets).
- QuickActions cell running-state accent: in the resized 4×1 icon-only variant no visible accent/elapsed change could be confirmed after the trampoline start (CareDashboard running row did update). Not separately filed — needs a check on the default 2×2 size.
