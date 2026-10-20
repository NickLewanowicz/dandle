---
title: "QA overview"
---

# QA tooling

**Read `docs/qa/RUNBOOK.md` first** — it defines the rules these scripts exist to
enforce (tap by testTag, never stale coordinates; verify server effects, not just
UI; never test blind). Flow specs with per-feature verification steps live in
`docs/qa/FEATURE-CHECKLISTS.md`.

## Script inventory (`scripts/qa/`)

| Script | Purpose |
| --- | --- |
| `lib.sh` | Shared helpers (dump-with-retry, bounds/text lookup by testTag, tap-by-tag). Sourced, not run. |
| `fresh-install-login.sh` | Force-stop + clear data + login, all by testTag; exits 0 only when `dashboard` is visible. |
| `ui-tap-tag.sh` | Resolve a resource-id in a fresh UI dump and tap its center (`--dump-after` to see the new tree). |
| `ui-text.sh` | Print a node's text by resource-id (e.g. read `login/error_banner`). |
| `applog.sh` | Stream (`-d` = dump once) device log filtered to `BB/*` + OkHttp. |
| `server-log.sh` | Baby Buddy docker request lines (gunicorn access log); `-f` follow, `-n N` tail. |
| `api.sh` | Authenticated curl (`METHOD path [json]`) against localhost:8000 using the current API token file (`BB_TOKEN_FILE` env var; regeneration per the API research doc). |
| `offline.sh` | Toggle emulator airplane mode `on`\|`off`, verified. |
| `server.sh` | `stop`\|`start`\|`wait` for the `babybuddy` docker container. |
| `shot.sh` | Screenshot to `docs/screenshots/<name>.png` (creates dirs; works from any cwd). |

All scripts honor `SERIAL` (default `emulator-5554`) and `ADB` env overrides.
