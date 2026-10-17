# Common flows — QA automation & runbook

**Owner:** qa-engineer persona · **Consumers:** every agent that touches the emulator, and future maintainers.

## Why this exists
AI agents repeatedly re-derive the same device dance (fresh install → login → drive UI → verify server) from scratch. It wastes tokens, bloats context, and produces stale-coordinate mistakes (the keyboard shifts the layout between taps — never reuse coordinates across steps). The scripts and runbook below are the canonical, cheap path.

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
4. **`capture-flows.sh --assert` is the pre-commit regression gate** for the save-visibility and edit-update canaries (diaper save appears under "Today" within 5 s without restart; edited time updates the row in place). Run it before committing UI changes; a FAIL blocks the commit.
5. The script handles the app being logged in or on the login screen, always restores airplane mode/server/timers/active child (even on failure), and deletes only the test entries it created (marker-tagged, via `api.sh DELETE`). Preconditions: server up, both widgets placed, children Milo + Zoe present.
