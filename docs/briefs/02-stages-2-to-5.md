# Implementation Stages 2–5 (post data-layer)

**Status:** Approved by Maintainer. Each stage is one android-engineer engagement, gated by build+tests, then QA on the emulator. The canonical behavior source is always [docs/flows/new-app/00-functional-spec.md](../flows/new-app/00-functional-spec.md) (v0.2) and [01-ux-spec.md](../flows/new-app/01-ux-spec.md).

## Stage 2 — Dashboard parity core (six quick actions + timeline)

Deliver: dashboard per spec §2–§4 and UX spec §2–§3 — child switcher (persisted), six quick-action buttons (order Feeding, Diaper, Sleep, Notes, Pumping, Tummy time), quick-action panels incl. running-timer state (live HH:MM:ss counter from TimerRepository), stop-guard dialog (>10s: Save emphasized / Discard / Keep; <10s silent), Feeding type→method cascade with **per-child learning defaults** (spec v0.2), Diaper wet/solid + extra-options disclosure, duplicate guard (spec §3.7), timeline (day-grouped, pull-to-refresh, scroll pagination, entry headlines per spec §4.1), long-press → open web (`{server}/{uiPath}/{serverId}/`, inert + explained when no serverId), SyncStatusChip (UX spec §5: Offline/Syncing…/N failed, tap = force sync), empty states. Timer restore across process death. Exit: full online parity core + offline writes visible as PENDING; tests for timer logic + learning defaults.

## Stage 3 — Quick edit + offline states (Feature 1 complete)

Del: QuickEditSheet per spec §5 + UX spec §4 (tap entry → sheet; type fields mirror §3; StepperTimeRow −15m/−1m/+1m/+15m with start-move-preserves-duration; picker; inline validation with save-disabled reasons; Edit + Delete-with-confirm; failed-entry Retry). Sync states per spec §6 + UX spec §3/§5: PENDING muted badge, FAILED errorContainer pill, failure dialog in parent language (name the clashing entry, jump-to-entry), session-expired reassurance copy with unsent-count. Reconnect → auto-drain → badges clear animated. Exit: all six spec §6.3 scenarios pass in JVM tests; QA runs them on emulator (server stop/start via docker).

## Stage 4 — Widgets (Feature 2)

Del: Glance widgets per spec §7 + UX spec §6: QuickActions (2×2, four configurable slots, default Sleep/Bottle/Diaper/Pump; running-state accent), CareDashboard (4×2 resizable; rows Last feed/diaper/nap/pump + running-timer row; "Last feed · 12m · 120 mL" format; minute granularity, refresh = app writes + sync completion + 15-min WorkManager + updateAll on demand). Tap actions: sleep row toggles timer via dedicated transparent Activity; feed/diaper rows deep-link into app with the panel pre-opened. Widget config (child, slots, row visibility) via Glance state. Widget data from Room snapshot only. Exit: widgets placeable on emulator launcher, tap actions verified, refresh verified after sync; Robolectric tests for the data-to-widget-state mapper.

## Stage 5 — Modern polish (Feature 3) + docs pass

Del: original vector icon set (all six actions + app icon + widget glyphs — no copied assets), motion per UX spec §7 budget (150–300ms, state-communicating only: counter, sheet, timeline insert/remove, badge transitions), final theming pass (dynamic color verified light/dark), Help slideshow (short, original content) + About (licenses, upstream links), settings display (server URL + username). Exit: UX-designer screenshot review passes with zero P0/P1; test-engineer audit of sync/mappers/timers complete; docs/screenshots/new-app/ + docs/flows/new-app/ per-flow files written by QA.
