# User persona panel — beta feedback round 1 (2026-10-17)

**Protocol:** Personas test the built app on the emulator (emulator-5554), one at a time (shared device), driven through `scripts/qa/*` per `docs/qa/RUNBOOK.md`. Each persona works from their own context — not from engineering docs — and writes a feedback file in this folder: `<name>.md` (walkthrough narrated in first person + verdict SHIP/FIX/REJECT + prioritized asks, each phrased as the experience wanted, never the implementation). They must VIEW the screens (screenshots via `scripts/qa/shot.sh` + Read) and may add events on the dev server (never delete seeded data). No code changes. After all sessions the Maintainer triages into `00-triage.md` (accept/defer/reject + rationale) and accepted items go to the engineer.

## The panel

| Persona | File | Context | Focus |
|---|---|---|---|
| **Jordan** | `jordan.md` | Parent of a 4-month-old; logs 10–20×/day one-handed, often at 3 a.m. from memory | Core logging speed, quick edit of wrong times, dark-room legibility, trust in sync states |
| **Sam** | `sam.md` | The partner; widget-first, opens the app reluctantly; logs ~2×/day | Home-screen widgets end-to-end, glance value, badge comprehension, "whose data is this?" |
| **Priya** | `priya.md` | Self-hosting tinkerer; reverse proxy, two kids, reads API logs for fun | Data fidelity vs server, timezone/edit correctness, offline replay, config screens, login model |
| **Elena** | `elena.md` | Grandmother; occasional caregiver; has never seen the app; low tech tolerance | First-run discoverability, label comprehension, Help slideshow, fear factors (badges, dialogs) |

## Session order (sequential)
1. Jordan → `jordan.md` feedback
2. Sam → `sam.md`
3. Priya → `priya.md`
4. Elena → `elena.md` (fresh install `pm clear` — she is the first-run test)

Each persona: ≤25 device interactions, restore demo state when done (app logged in, widgets placed, no running timers unless noted).
