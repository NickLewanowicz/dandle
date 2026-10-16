# The Team — Agent Personas

This project is built by an AI-driven team. One orchestrator (the **Maintainer**) plus five persona subagents. The persona definitions live in `.zcode/agents/*.md` so any ZCode session in this repo can spawn them; this document explains the team and how it works together.

## Why personas

Baby Buddy for Android succeeds because of restraint: six buttons, fast timers, nothing extra. Replicating that discipline with AI builders requires explicit role boundaries — a persona that is simultaneously designer, engineer, and reviewer has no one to say "no" to it. Each persona below has a narrow mandate and a defined deliverable.

## The roster

| Persona | File | Mandate | Typical deliverable |
|---|---|---|---|
| **Maintainer** (orchestrator) | *(this role is played by the primary agent)* | Owns scope. Weighs feature proposals (pros/cons → recommendation → reasonable scope), writes briefs, sequences work, accepts or rejects results. | Feature briefs, the roadmap, release decisions |
| **UX Designer** | `.zcode/agents/ux-designer.md` | Turns briefs into concrete screen specs; reviews implemented screens; enforces "speed to log beats completeness". | Screen specs, P0–P2 critiques |
| **User Representative ("Jordan")** | `.zcode/agents/user-persona.md` | Role-plays a sleep-deprived parent; evaluates flows for speed, one-handed use, forgiveness, offline trust. | Flow walkthroughs with a ship/fix/reject verdict |
| **Android Engineer** | `.zcode/agents/android-engineer.md` | Implementation strategies and Kotlin/Compose code; challenges specs before coding. | Strategies, code, verified builds |
| **QA Engineer** | `.zcode/agents/qa-engineer.md` | Black-box testing on the emulator via UI automation; screenshot evidence; offline/sync scenario testing; precise bug reports. | Verified flows, screenshots, P0–P3 bug reports |
| **Test Engineer** | `.zcode/agents/test-engineer.md` | Unit/integration tests for the layers where bugs hide (sync engine, mappers, ViewModels, timer logic). | Green suite, coverage notes, exposed bugs |

## How a feature moves

```
 proposal ──► Maintainer brief ──► UX spec ──► user-persona review
                                                    │
    docs/screenshots ◄── QA on emulator ◄── engineer implements ◄─┘
            │                        ▲
            └── flows doc updated    └── test-engineer pins behavior
```

1. **Brief** — Maintainer writes what & why, with explicit non-goals.
2. **Spec** — UX Designer produces the screen/flow spec; Jordan reviews it ("can I do this at 3 a.m. with one hand?").
3. **Strategy** — Android Engineer challenges the spec against the API research and proposes an implementation plan.
4. **Build** — Engineer implements; Test Engineer adds tests for sync/offline logic.
5. **Verify** — QA drives the flow on the emulator, screenshots everything, tests the offline path (Docker server stopped/started), files bugs.
6. **Accept** — Maintainer checks the flow doc + screenshots against the brief; only then is the feature done.

## The human's role

The human is the product owner: proposes features (e.g. "quick time edit", "offline logging", "widgets"), arbitrates genuine product tradeoffs, and uses the beta. Everything else is delegated to the team above.
