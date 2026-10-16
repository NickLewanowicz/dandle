# babybuddy-next — Working Agreement

A modern, offline-first Android client for [Baby Buddy](https://github.com/babybuddy/babybuddy) (self-hosted baby tracker), rebuilt from scratch with an AI-driven team. Scope parity with [Baby Buddy for Android](https://github.com/babybuddy/babybuddy-for-android) (v2.6.4) plus three deliberate additions: in-app quick-edit of events, offline queue with conflict surfacing, and home-screen widgets — all in a Material 3 shell.

## Non-negotiables

0. **The functional spec is the rewrite contract.** Every user path, feature, state, and flow must be documented in `docs/flows/` (with screenshots in `docs/screenshots/`) so the entire app could be re-implemented on a different framework from the docs alone. Code is disposable; the spec is the product of record. If a behavior isn't specced, it isn't done.
1. **Do not explode scope.** The original app's restraint is a feature. New ideas go through the Maintainer (orchestrator): pros/cons, recommendation, reasonable scope — before any code.
2. **Match the server exactly.** API behavior is documented in `docs/research/babybuddy-api.md` (verified against server source). Field names, validation rules, and error shapes are law.
3. **Match the original UX where it works.** The six quick actions (Feeding, Diaper, Sleep, Notes, Pumping, Tummy time), tap-to-toggle timers, and long-press-to-web are pinned behavior. Flow specs live in `docs/flows/`. Change flows only via a Maintainer-approved brief.
4. **Offline-first.** The Room DB is the UI's source of truth. Server sync is a background concern; every create/edit works with no network and lands in a sync queue. Failures surface in-app with a resolution path (edit or delete locally) — never a dead-end error.
5. **Always keep the escape hatch.** Long-press on any entry opens the same event on the Baby Buddy web UI (`{server}/{uiPath}/{id}/`). The web UI always has more fields than us; that's fine and intentional.
6. **All original-scope flows must be documented** in `docs/flows/` with screenshots in `docs/screenshots/` before we call them done.

## Repo layout

```
app/                    # Android app (Kotlin, Jetpack Compose, Material 3)
docs/
  research/             # Verified references: Baby Buddy API, existing-app architecture
  flows/                # User-flow specs: existing app (QA-mapped) and this app
  screenshots/          # Screenshot evidence, organized by area
  personas/             # Agent persona documentation
scripts/                # Dev utilities (seed_demo_data.py, etc.)
.scratch/               # gitignored: reference clones of babybuddy + babybuddy-for-android
.zcode/agents/          # Persona definitions for ZCode subagents
```

## Dev environment (this machine)

- **Baby Buddy server**: Docker container `babybuddy` → `http://localhost:8000` (host) / `http://10.0.2.2:8000` (emulator). Login `admin` / `admin`. API token in `/tmp/bb_token` (regenerate: see `docs/research/babybuddy-api.md`). Seed data: `python3 scripts/seed_demo_data.py`.
- **Emulator**: AVD `Pixel5` (`emulator-5554`), driven via the android-emulator MCP tools.
- **Build**: `./gradlew :app:assembleDebug :app:testDebugUnitTest` (JDK 17).

## How we work (AI-driven team)

The Maintainer orchestrates persona subagents (see `.zcode/agents/` and `docs/PERSONAS.md`): **android-engineer** implements, **test-engineer** pins behavior with tests, **qa-engineer** verifies on the emulator with screenshots, **ux-designer** specs/reviews screens, **user-persona** ("Jordan") vetoes anything slow or scary. Feature lifecycle: brief → engineer strategy → implement + tests → QA on emulator → docs/screenshots → done.

## Conventions

- Kotlin; single-module app is fine at this size — split when build times or test boundaries demand it.
- Compose + Material 3 with dynamic color; motion only when it communicates state.
- Every network-dependent behavior must have a unit test for its offline path.
- No copying of the original app's icon/media assets (attribution-licensed); our own visuals only. MIT/BSD heritage is respected: we take inspiration, not assets.
