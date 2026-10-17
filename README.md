# Baby Buddy Next

A modern, offline-first Android client for [Baby Buddy](https://github.com/babybuddy/babybuddy), the open-source self-hosted baby tracker — rebuilt from scratch by an AI-driven team, with a couple of creature comforts the original Android app never had.

**Status: feature-complete beta, in QA.** The app builds, installs, and runs against a real Baby Buddy server; automated QA is documenting the flows in `docs/flows/new-app/` with screenshot evidence.

## Why this exists

[Baby Buddy for Android](https://github.com/babybuddy/babybuddy-for-android) is a wonderful little app: six buttons, fast timers, diaper logging — nothing extra. Its README says it plainly: the maintainer runs it as a hobby alongside a 9–5 and a house move, and development is paused. We're grateful for those years of daily use.

This project is what happens when a family that relies on that app daily wants three things it doesn't have — and can build them:

1. **Quick time edits in the app.** The original app's most-used "power feature" is long-pressing an event to bounce to the web UI just to fix a time. We kept the long-press escape hatch (the web app is always the superset), but tapping an entry now opens a fast in-app editor: nudge a diaper change ±5 minutes, fix when a feed started, done.
2. **Offline support.** Self-hosted means sometimes the server is down, and parenthood means sometimes you're at a park with no signal. Events log locally and replay to the server when it's reachable. If the server rejects something (e.g. an overlapping nap), the entry shows a quiet "failed" state with the server's reason and a path to fix or remove it — locally, in the app.
3. **Home-screen widgets.** A small widget to start a sleep or pump timer without opening the app, and a larger one showing *time since* the last feed, diaper, nap, or pump — tap a row to log or start a timer.

All of it wrapped in a current Material 3 look — dynamic color, dark mode, motion that communicates state — while keeping the original's layout discipline: six buttons up top, timers, timeline. **The scope is deliberately small.** Restraint is why the original worked.

## An AI-driven project, out in the open

This codebase is built and maintained by AI agents under a human product owner. The team roles (personas), their mandates, and the feature lifecycle are documented in [`docs/PERSONAS.md`](docs/PERSONAS.md) and `.zcode/agents/`.

Two things make that workable:

- **The spec is the product.** Every user path, screen state, and flow is documented in [`docs/flows/`](docs/flows/) with screenshot evidence in [`docs/screenshots/`](docs/screenshots/). The canonical functional specification — [`docs/flows/new-app/00-functional-spec.md`](docs/flows/new-app/00-functional-spec.md) — describes the entire app framework-agnostically, so the implementation is replaceable and every change is checkable against the contract.
- **QA is automated.** A QA-engineer agent drives the app on an Android emulator against a real Baby Buddy server (Docker), walks every flow, exercises the offline path, and archives screenshots as documentation.

## Upstream credit & licenses

- [Baby Buddy](https://github.com/babybuddy/babybuddy) (BSD-2-Clause) — the server this app connects to. This project is an API client; it is not affiliated with the Baby Buddy project.
- [Baby Buddy for Android](https://github.com/babybuddy/babybuddy-for-android) (MIT code) — our scope baseline and UX inspiration. We deliberately do **not** copy its icons or media (attribution-licensed); all artwork here is original.

## Development (this repo)

See [AGENTS.md](AGENTS.md) for the full working agreement. Short version:

- **Server**: `docker run -d --name babybuddy -p 8000:8000 -v babybuddy_config:/config lscr.io/linuxserver/babybuddy` (default login `admin`/`admin`), seed with `python3 scripts/seed_demo_data.py`.
- **Build**: `source scripts/dev-env.sh && cd app && ./gradlew :app:assembleDebug :app:testDebugUnitTest` (uses the Android Studio JBR; the system JDK breaks Gradle). Debug builds install as `net.babybuddy.next.debug`.
- **Emulator QA**: `scripts/qa/fresh-install-login.sh` and friends — see [`docs/qa/RUNBOOK.md`](docs/qa/RUNBOOK.md). Debug builds log every network call and user action under `BB/*` tags (`scripts/qa/applog.sh`).
- **Docs**: `docs/research/` (verified API + original-app internals), `docs/flows/` (user flows old & new, with the canonical [functional spec](docs/flows/new-app/00-functional-spec.md)), `docs/briefs/` (maintainer briefs incl. the [framework evaluation and decision](docs/briefs/framework-eval/DECISION.md)), `docs/qa/` (runbook + per-feature test checklists).

## Tech

Native Kotlin + Jetpack Compose + Material 3 (dynamic color), Room (offline-first, outbox pattern), WorkManager (background replay), Glance (widgets), Retrofit/OkHttp. Single module, manual DI, ~7k lines of app code + 170+ JVM tests. Why native and not Flutter/React Native: [DECISION.md](docs/briefs/framework-eval/DECISION.md) — the short version is that the features that differentiate this app (widgets, Material You, background sync) are native features, and the AI-agent QA loop on the emulator was measured at under 10 seconds.
