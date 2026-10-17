# Persona round 1 — Maintainer triage (FINAL 2026-10-17)

Inputs: [jordan.md](jordan.md) · [sam.md](sam.md) · [priya.md](priya.md) · [elena.md](elena.md) · Verdicts: 4× FIX FIRST (0 REJECT). Consensus #1 across all four personas: saves must appear in the timeline instantly (T1). The core logging, wire fidelity, parent-language failure dialog, widgets' glance value, and duplicate guard all landed as intended.

## Triage table

| # | Ask (source) | Decision | Rationale / notes |
|---|---|---|---|
| T1 | **Saves must appear in the timeline instantly** (Jordan, Sam, Priya — independent) | **ACCEPT — P1 REGRESSION** | Our own P1-1 fix regressed or was incomplete: Priya proved data is in Room while the UI is frozen, so this is a Flow-collection/reactivity bug in the timeline, not the data layer. The `DashboardTimelineLiveUpdateTest` apparently doesn't pin the regressed path — find why, add the missing pin, fix. Gated by capture-flows `--assert` canary going forward. |
| T2 | "Started 20 min ago" fix on the quick-action panel itself (Jordan: 13 interactions is too many) | **ACCEPT — P2** | Add a compact "started X min ago → adjust" affordance on timed-type panels (±5/±15 quick nudges + picker), pre-save. Duration preserved on start-moves (existing rule). Spec §5 update + capture set. |
| T3 | Offline/sync legibility: no "Will sync" badge seen, no "all caught up" moment (Jordan) | **ACCEPT — P2** | Spec §6.2 badges exist but evidently aren't visible in the observed offline flow — investigate why (badge only on FAILED? pending state painted too subtle?). Also add a subtle "All synced" transition on the chip. UX spec §5 alignment. |
| T4 | Home screen must speak for both kids; "Milo" header too quiet (Sam) | **ACCEPT — P2** | CareDashboard: child name more prominent + an affordance to switch/configure per-child from the widget; when timers/rows for another child exist, the QuickActions running state should say whose. (Scope guard: no multi-child dashboard in beta.) |
| T5 | "Tap to stop" lie on dashboard widget row (Sam) | **ACCEPT — P2 copy fix** | The row opens the app (guard dialog can't run from a widget). Relabel to "Running: Sleep — open" or have the app open straight INTO the stop-guard dialog so the tap does what it says. Engineer picks; spec §7.2 note. |
| T6 | Edit sheet leaks robot-speak + wrong timezone (Sam) | **ACCEPT — P2** | Investigate: the sheet's stored-value line shows a shifted timezone (likely rendering UTC instead of local for PENDING/offline entries). All user-visible times must be device-local. |
| T7 | Cross-child running-timer awareness (Priya: "forgotten-nap factory") | **ACCEPT — P3** | When a timer runs for another child, show a quiet one-line indicator ("Zoe's nap running · 32m") on the dashboard. Not a switch. |
| T8 | API key: paste-token option + exclude from backups (Priya) | **ACCEPT — P2** | `allowBackup=false` (or backup rules excluding the prefs) + an "advanced: paste API key" path on login. Matches original app's threat model; cheap. |
| T9 | Widget picker previews (from UX round) already fixed; Elena pending | — | Confirm with Elena's session. |
| T10 | Login: Sign-in silently disabled with no reason; "Unencrypted connection" reads accusatory (Elena) | **ACCEPT — P2 copy/UX** | Show why submit is disabled (inline hint "Enter your server URL") and soften cleartext copy to neutral-informational ("This server uses http:// — data is not encrypted. Continue?"). |
| T11 | Sleep panel lacks an "already asleep" backdated start control (Elena; reinforces Jordan T2) | merged into T2 | T2 covers timed-type panels incl. Sleep. |

## Sequencing
1. Engineer engagement "round 1 fixes": T1 (regression, with capture `--assert` green) → T6 → T5 → T3 → T2 → T8 → T4 → T7. One commit per item, suite + capture-flows full run green, updated flow PNGs committed with the changes.
2. Functional spec v0.3 deltas: T2 (panel time-adjust), T3 (badge visibility rules), T4 (widget child prominence), T5 (row copy/behavior), T7 (cross-child indicator).
