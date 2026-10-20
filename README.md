# Baby Buddy Next

A modern, offline-first Android client for [Baby Buddy](https://github.com/babybuddy/babybuddy), the open-source, self-hosted baby tracker.

**Status: beta.** It builds, installs, and runs against a real Baby Buddy server. It is not on an app store yet.

## Why I'm building this

My wife and I have gotten a lot of value out of Baby Buddy — both the service and [Baby Buddy for Android](https://github.com/babybuddy/babybuddy-for-android). That app is exactly what it should be: six buttons, fast timers, diaper logging, nothing extra. We use it every day, several times a day.

There were a few things we kept wanting that it doesn't do, so I'm building them:

1. **Offline support.** Self-hosted means sometimes the server is down, and parenthood means sometimes you're at a park with no signal. Events log locally and replay to the server when it's reachable. If the server rejects something (an overlapping nap, say), the entry shows a quiet "failed" state with the server's reason and a path to fix or remove it — in the app, not a dead-end error.
2. **Editing times in the app.** The old flow for fixing a time was long-press → web UI → find the field. Here, tapping an entry opens a fast in-app editor with native date/time pickers: nudge a diaper change, fix when a feed actually started, done. The long-press escape hatch stays — the web UI always has more fields than this app, and that's intentional.
3. **Home-screen widgets.** A small widget to start a sleep or pumping timer without opening the app, and a larger one that shows where things stand — running timer first, then time since the last feed, diaper, nap, or pump. Tapping a row opens the app.

The scope is deliberately small. Restraint is a big part of why the original works, and I'm not trying to reinvent it: the six quick actions, tap-to-toggle timers, and the long-press-to-web behavior are kept as they are.

I'm doing this for my family first. Hopefully somebody else gets some value out of it too.

## How it's built

I'm a software engineer with over ten years of experience, and a substantial amount of that time has gone into managing AI agents — so this project is built the way I work day to day. I set the direction, review the output, and make the calls; a small team of AI agents does the implementation, testing, and QA under that supervision. The personas, their mandates, and the feature lifecycle are documented in [`docs/PERSONAS.md`](docs/PERSONAS.md) and [`.zcode/agents/`](.zcode/agents/).

Two things make that setup actually work:

- **The spec is the product.** Every user path, screen state, and flow is documented in [`docs/flows/`](docs/flows/) with screenshot evidence in [`docs/screenshots/`](docs/screenshots/). The canonical functional specification — [`docs/flows/new-app/00-functional-spec.md`](docs/flows/new-app/00-functional-spec.md) — describes the entire app framework-agnostically, so the implementation is replaceable and every change is checkable against the contract.
- **QA is automated.** A QA-engineer agent drives the app on an Android emulator against a real Baby Buddy server (Docker), walks every flow, exercises the offline path, and archives screenshots as documentation. A `--assert` regression gate re-verifies the critical flows on every UI change.

## Upstream credit & licenses

- [Baby Buddy](https://github.com/babybuddy/babybuddy) (BSD-2-Clause) — the server this app connects to. This project is an API client; it is not affiliated with the Baby Buddy project.
- [Baby Buddy for Android](https://github.com/babybuddy/babybuddy-for-android) (MIT code; icon artwork attribution-licensed) — our scope baseline and UX inspiration. Nearly all artwork here is original. One deliberate exception: the breastfeeding side icons (left / right / both) are a close recreation of Baby Buddy for Android's breast-feeding icons with minor adjustments to stroke weight and corners — for those shapes, clarity for a tired parent beats originality, and the credit belongs to the original project.

## Development (this repo)

See [AGENTS.md](AGENTS.md) for the full working agreement. Short version:

- **Server**: `docker run -d --name babybuddy -p 8000:8000 -v babybuddy_config:/config lscr.io/linuxserver/babybuddy` (default login `admin`/`admin`), seed with `python3 scripts/seed_demo_data.py`.
- **Build**: `source scripts/dev-env.sh && cd app && ./gradlew :app:assembleDebug :app:testDebugUnitTest` (uses the Android Studio JBR; the system JDK breaks Gradle). Debug builds install as `net.babybuddy.next.debug`.
- **Emulator QA**: `scripts/qa/fresh-install-login.sh` and friends — see [`docs/qa/RUNBOOK.md`](docs/qa/RUNBOOK.md). Debug builds log every network call and user action under `BB/*` tags (`scripts/qa/applog.sh`).
- **Docs**: `docs/research/` (verified API + original-app internals), `docs/flows/` (user flows old & new, with the canonical [functional spec](docs/flows/new-app/00-functional-spec.md)), `docs/briefs/` (maintainer briefs incl. the [framework evaluation and decision](docs/briefs/framework-eval/DECISION.md)), `docs/qa/` (runbook + per-feature test checklists).
- **Docs site**: Docusaurus (the stack Immich uses) — the content stays canonical in `docs/`; the site itself lives in `website/`. Preview: `cd website && npm ci && npm start`; static build: `npm run build` (output `website/build`). Deploys to GitHub Pages via `.github/workflows/deploy-docs.yml` on every push touching docs or the site; Cloudflare Pages recipe: build command `cd website && npm ci && npm run build`, output dir `website/build`. Flow demo GIFs/MP4s live in `docs/media/` (mirrored into `website/static/media/` so `/media/…` URLs are stable).

## Tech

Native Kotlin + Jetpack Compose + Material 3 (dynamic color), Room (offline-first, outbox pattern), WorkManager (background replay), Glance (widgets), Retrofit/OkHttp. Single module, manual DI, ~9.9k lines of Kotlin + 219 JVM tests. Why native and not Flutter/React Native: [DECISION.md](docs/briefs/framework-eval/DECISION.md) — the short version is that the features that differentiate this app (widgets, Material You, background sync) are native features, and the AI-agent QA loop on the emulator was measured at under 10 seconds.
