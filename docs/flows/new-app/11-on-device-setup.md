---
title: "Flow verification: On-device mode (device-only tracking)"
---

# 11 — On-device mode (brief 05 §4) — QA flow doc

**Purpose:** prove the no-server backend: setup, fully-offline event capture with no sync queue growth, correctly-gated web escape hatch, and the wipe-on-sign-out semantics.

## How to verify

1. Fresh state (fresh install, or sign out): tap `login/mode_device`.
2. Child setup ("Your baby"): `login/child_name` required; `login/child_birth_date` OPTIONAL (tap the field or `login/child_date_pick`; blank stores today; future dates show "Birth date can't be in the future.") → `login/child_submit`. Disabled button names what's missing ("Add a name to continue").
3. **Offline by construction:** device mode has no server — airplane mode is OPTIONAL. For explicit proof: `scripts/qa/offline.sh on` before saving.
4. Save a diaper (`dashboard/quick_action/CHANGE` → wet → save). Row appears under Today immediately.
5. Verify LOCAL sync semantics: the unsynced chip must stay at zero (`dashboard/sync_chip` shows no pending count); app logs (`scripts/qa/applog.sh`, `BB/SYNC`) show the inline drain completing with zero network calls; outbox stays empty.
6. Escape hatch: `dashboard/timeline` TAP opens Quick Edit (all backends); LONG-PRESS is the web hatch and is intentionally inert on non-server backends — no browser, no snackbar (QA verified 2026-11-01 post-fix: leak closed, long-press silent; server mode still deep-links). *Correction 2026-11-01: an earlier revision wrongly claimed long-press opens Quick Edit — tap is edit, long-press is the hatch (spec §4.2).*
7. Sign out: overflow → Sign out → dialog must read **"This permanently clears all data on this device. There is no server copy."** → confirm → mode picker; re-entering device mode shows empty child setup.

Tags: `login/mode_device`, `login/child_name`, `login/child_birth_date`, `login/child_date_pick`, `login/child_submit`, `dashboard/quick_action/CHANGE`, `dashboard/sync_chip`, `dashboard/timeline`.
JVM tests: `LocalBackendTest` (inline drain with no network, minted stable ids across edits, delete completes, unsent stays 0, pull reports success, LWW tombstone policy off).

## Observed (2026-11-01, build on emulator-5554)

1. Child setup → dashboard with "QA Device Kid"; airplane-mode diaper save appeared instantly, sync chip clean, `BB/SYNC` logs show LOCAL pusher completing inline.
2. Sign-out dialog copy verified (device wipe wording); sign-out clears the child (re-entry shows empty setup).
3. Long-press hint never shown in device mode; long-press opens Quick Edit as usual.

## Branches / states

| State | Result |
|---|---|
| No name | Submit disabled: "Add a name to continue" |
| Future birth date | Field error "Birth date can't be in the future." |
| Save while offline | Row SYNCED locally; unsent count 0; no offline error |
| Sign out | Confirm-wipe copy; data cleared; back to picker |

## Screenshots

`docs/screenshots/new-app/qa-multi-backend/` — `device-child-setup.png`, `device-offline-save.png`, `device-signout-wipe.png`.

## Deviations from spec

- AGENTS.md #5 (long-press-to-web) applies to server backends only — there is no web UI to open in device mode (owner-approved amendment, brief 05 D10).
