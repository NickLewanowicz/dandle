---
title: "QA runbook"
---

# Common flows — QA automation & runbook

**Owner:** qa-engineer persona · **Consumers:** every agent that touches the emulator, and future maintainers.

## Why this exists
AI agents repeatedly re-derive the same device dance (fresh install → login → drive UI → verify server) from scratch. Repeating that dance by hand is slow, error-prone, and produces stale-coordinate mistakes (the keyboard shifts the layout between taps — never reuse coordinates across steps). The scripts and runbook below are the canonical, cheap path.

## The rules (also in AGENTS.md)
1. **Never hand-drive a fresh-install login again** — use `scripts/qa/fresh-install-login.sh`.
2. **Tap by testTag, not by remembered coordinates**: `scripts/qa/ui-tap-tag.sh <resource-id>` dumps the accessibility tree, resolves the tag's center, taps it, and re-dumps on demand. Coordinates go stale the moment a keyboard or dialog appears.
3. **Verify server effects, not just UI**: `scripts/qa/server-log.sh` (tail Baby Buddy access logs), `scripts/qa/api.sh <METHOD> <path> [json]` (authenticated curl using the current token).
4. **Offline scenarios**: `scripts/qa/offline.sh on|off` (airplane mode), or stop/start the Docker server with `scripts/qa/server.sh stop|start`.
5. **Screenshots are documentation**: `scripts/qa/shot.sh <relative-path-under-docs/screenshots>`.
6. **Debug logging is on by default in debug builds** (`BB/*` log tags; OkHttp request/response logging). During any test run, tail with `scripts/qa/applog.sh` — do not test blind.
7. **Every feature's flow doc includes a "How to verify" section** naming: the script(s) to run, the testTags involved, and the expected server requests. A feature without this section is not done.

## Feature test checklists
`docs/qa/FEATURE-CHECKLISTS.md` maps each shipped feature to its concrete verification steps (both emulator flows and JVM tests). When a feature is added, its checklist entry is added in the same change.

## Flow capture & regression canaries

`scripts/qa/capture-flows.sh` drives the app through **every** documented flow state (login, dashboard, all six quick actions + dialogs, quick edit, offline/failed/synced, widgets + configs, help/about) and captures the numbered PNG set under `docs/screenshots/new-app/flows/`. Rules:

1. **The committed PNGs under `docs/screenshots/new-app/flows/` are the visual source of truth for docs.** Personas/reviewers evaluate flows from that set, not by hand-driving the emulator. Never hand-capture these screenshots — regenerate them with the script.
2. **When to run it:** after ANY UI-affecting change, before documenting a feature as done, and before cutting a release.
3. **Same-commit rule:** a change that alters a flow must regenerate and commit the affected PNGs in the same commit as the change.
4. **`capture-flows.sh --assert` is the pre-commit regression gate** for the save-visibility and edit-update canaries (A1: a diaper saved from the sheet appears under "Today" within 12 s without restart/refresh; A2: a START moved through the native time picker updates the sheet value and then the timeline row in place within 10 s; A3: restarting the app while a timer runs re-opens the card with the adopted timer and its STARTED row reads the timer's real age — "…m ago", never "just now") plus the overlap → FAILED badge → edit-back → retry → cleared scenario. Run it before committing UI changes; a FAIL blocks the commit. The edit sheet and create sheets are dialog windows — their tags reach the dump only because every sheet applies `dialogTestTags()`; the time picker is driven by `pick_time` (dial content-descs + a minute drag).
5. The script handles the app being logged in or on the login screen, always restores airplane mode/server/timers/active child (even on failure), and deletes only the test entries it created (marker-tagged, via `api.sh DELETE`). Preconditions: server up, both widgets placed, children Milo + Zoe present.
6. **The gate syncs the APK itself** (`sync_apk`: `:app:assembleDebug` + `adb install -r` + `lastUpdateTime`-changed guard) — it refuses to capture a stale binary. This was added 2026-10-19 after a stale-install incident: the gate rebuilt but never installed, and a full PNG set was captured and committed against the previous build. `source scripts/dev-env.sh` first (Gradle needs the JBR).
7. **Keep human/demo entries out of the capture's edit windows.** The A2 edit moves its target entry's START one hour earlier; if any REAL (unmarked) entry's span covers that wall-clock window, the server legitimately rejects the edit as an overlap and the entry goes FAILED — A2b and the overlap-resolve canary then fail as a cascade (2026-10-19: a hand-saved 18:09–19:11 breastfeed did exactly this). Before a gate run, delete or reschedule demo entries overlapping roughly the last 2 hours; hand-saved demo data belongs in the quieter parts of the day.
8. **Known flaky steps (2026-10-20):** group 05's offline-restart → server-down → drain sequence
   flaked in three consecutive full-gate runs (dashboard-not-visible cascade after airplane-mode
   restarts; the app itself verified fine offline by hand). `06-widgets-03-home-sleep-running.png`
   was captured out-of-gate (documented deviation — same steps, driven by hand with dump-verified
   coordinates) and `05-sync-states-02-serverdown-pending.png` is intentionally absent pending a
   fix of the step sequence. Do not "restore" these files from old runs — a stale capture lies.
9. **Pushing to Gitea (tower):** the `gitea` remote points at the public
   `https://git.imnick.dev/nick/dandle.git` (Cloudflare-fronted; migrated from
   git.imnick.dev 2026-10-31 — a global `insteadOf` rewrite in `~/.gitconfig`
   maps any stray old-domain URL to the new one). Large pushes (the initial
   history, or any pack with many flow PNGs) get rejected with **HTTP 413** by
   the proxy — push big packs over the LAN instead:
   `git push http://192.168.86.150:3010/nick/dandle.git main` (token auth same
   as the public remote; the token lives in this repo's gitea remote URL).
   Small day-to-day pushes work through the public URL. The Gitea SSH endpoint
   on tower is port 2222 if key auth is ever set up.
