# New app — Widgets (spec §7, Stage 4)

QA reference for the two Glance widgets. Implementation: `app/app/src/main/java/net/babybuddy/next/widget/`.
Checklists: `docs/qa/FEATURE-CHECKLISTS.md` entries 9–10. Tags: `BB/UI`, `BB/SYNC`, `BB/TIMER`, `BB/API`.

## QuickActions (2×2 → resizable; renders 4×1 icon-only when wide)

- Four configurable slots (configure: child + 4 buttons; default **Sleep / Bottle / Diaper / Pump**).
- **Idle timer slot tap** → starts the timer via broadcast (`WidgetSleepStartAction`); NO app launch;
  both widgets refresh (`BB/TIMER start`, `POST /api/timers/`).
- **Running slot tap (T5, spec §7.2)** → deep-links into the app **directly into the stop-guard
  dialog for that timer** (`EXTRA_STOP_TIMER` intent extra → `WidgetDeepLink.stopRequest` → the
  dashboard runs the exact in-app `requestStopGuard` semantics: >10s → "Stop timer?" with
  Save/Discard/Keep; ≤10s → silent discard, same as the in-app ✕). The slot reads "tap to stop";
  the tap does what it says. Widgets still cannot host the dialog themselves — the app hosts it.
  If the timer vanished before the tap lands (stopped on another device), the request falls back
  to the plain quick-action semantics (panel focus) instead of stranding.
- **Bottle / Diaper / Pump taps** → deep-link into the app with that panel pre-opened
  (`EXTRA_OPEN_PANEL` intent extra → `WidgetDeepLink` channel → dashboard runs the exact in-app
  tap semantics once the active child resolves; works cold-start and across the login gate).

## CareDashboard (4×2, resizable 4×3)

- Rows: Last feed (`12m · 120 mL`), Last diaper (`34m · Wet`), Last nap (`2h · 45m`), Last pump —
  minute-granular, staleness documented (spec §7.2). When a timer is active, the running-timer
  row renders as the widget's FIRST row, accented ("Asleep since 13:00 · 12m elapsed" wording
  shape); feeding rows use method-specific icons (left / right / both / bottle). Entry
  timestamps on rows follow the same >5-minute range rule as the timeline.
- Taps: ALL info rows open the app — feed/diaper/pump rows deep-link with the panel pre-opened,
  and the nap row opens the app too (timers are never started from the widget; this supersedes
  the earlier broadcast-stays-on-launcher behavior, see `50-widgets.md` finding #5). Running row
  (T5) → app opens straight into the stop-guard dialog.

## Persona round 2 (2026-10-18)

Re-verified on device; specified behavior now:

- **Running-first row (CareDashboard):** a running timer renders as the widget's FIRST row in a
  running state, wording like "Asleep since 13:00 · 12m elapsed" (the old "Running: Sleep —
  Xm — tap to stop" is gone).
- **Info rows open the app:** tapping any info row (sleep/feed/diaper/pump) opens the app; no
  timers are ever started from the widget and nothing runs from the launcher.
- **Method-specific feeding icons:** feeding rows show a left / right / both / bottle icon.
- **QuickActions cells:** idle cells show a subdued second line with the last event's compact
  relative time ("45m" / "1h30m" / "2d"); a running cell is highlighted in the type color with
  a live elapsed label ("Sleep · 12m").
- **Range rule on widget timestamps:** entry timestamps follow the same >5-minute rule as the
  timeline — a start–end range (duration on the second line) for entries longer than 5 minutes
  or crossing local midnight, plain time-of-day for shorter entries; sub-minute renders "<1m".

## Refresh (spec §7.2)

In-app writes, outbox drain completion (`SyncWorker`), trampoline actions, and a 15-min periodic
WorkManager job (`WidgetPeriodicWorker`) all call `WidgetRefresher.refreshAll` → both widgets
re-read their Room snapshot. No per-second ticking anywhere (minute granularity, documented).

## Config (spec §7.3)

Long-press → Configure: child + four slots (QuickActions) / child + row visibility (CareDashboard).
Per-widget Glance Preferences state; one-tap add defaults to the active child.

## Known launcher notes (emulator smoke, API 32/33)

- Placement opens the configure screen automatically; Save renders the widget in resize mode —
  tap empty space to commit.
- The widget picker lists the app as "Baby Buddy Next — 2 widgets".
