---
title: "Flow verification: Login"
---

# 10 — Login (spec §1.1–1.2, brief 04) — QA flow doc

**Purpose:** prove the fresh-install path for all three data modes — the **mode picker** landing ("Where should your data live?"), the pinned Baby Buddy server flow (URL verify → cleartext warning → credentials), the on-device child setup, and Dandle Cloud sign-in/create — fully scripted where possible, with server-side request chains verified.

> Brief 04 rebrand: the login screen opens on the **Dandle** brand block (generated badge + wordmark + "Log the day, one tap at a time.") and the mode picker. The Baby Buddy server sub-flow is unchanged pinned behavior; a stored-session re-auth (session-expired) lands **directly on the URL step** — the picker is never shown for re-auth.

## How to verify

- Script: `scripts/qa/fresh-install-login.sh` (defaults: `app.dandle.debug`, `http://10.0.2.2:8000`, `admin`/`admin`) — taps `login/mode_server` on the picker, then the URL step. Exit 0 only when the `dashboard` tag is visible. Add `--pass wrongpass` for the failure branch (script exits 2 and prints `login/error_banner` text).
- Tags: `login/mode_device`, `login/mode_server`, `login/mode_cloud`, `login/back`, `login/server_url`, `login/username`, `login/password`, `login/submit`, text node `Continue` (cleartext dialog), `login/error_banner`, `dashboard`; device/cloud flow: `login/child_name`, `login/child_birth_date`, `login/child_date_pick`, `login/child_submit`, `login/cloud_create_confirm`, `login/cloud_create_confirm_ok`; cloud flow: `login/cloud_tab/sign_in`, `login/cloud_tab/create`, `login/cloud_username`, `login/cloud_password`, `login/cloud_passphrase`, `login/cloud_passphrase_confirm`, `login/cloud_passphrase_warning`, `login/cloud_submit`.
- Logs: `scripts/qa/applog.sh` (`BB/LOGIN`, OkHttp), `scripts/qa/server-log.sh -f`.
- Expected server requests (server mode): `GET /login/` (csrf) → `POST /login/` 302 → `GET /api/profile` → `GET /api/children/?limit=1` → full sync chain (`GET /api/children/?limit=100`, six per-type lists). No other 4xx. Device mode: **no network requests at all**. Cloud mode: `POST /api/collections/users/auth-with-password` (+ record CRUD on `sync_records` over TLS to pockethost).

## Branches / states

| State | Result |
|---|---|
| Fresh install | Mode picker (brand block + three cards; stakes copy under the cards) |
| Picker → This device only | Child setup (name + birth date) → dashboard, no network |
| Picker → Baby Buddy server | URL step → cleartext dialog → credentials → dashboard |
| Server mode, wrong password | `login/error_banner` "Wrong username or password." |
| `http://` URL | Cleartext dialog, Continue proceeds |
| Stored session, relaunch | Login skipped entirely |
| Session expired (server) | Lands DIRECTLY on URL step, fields pre-filled, info banner |
| Cloud create: passphrase mismatch | Live field error "Passphrases don't match.", submit gated |
| Cloud create: submit | Confirm dialog "Save your passphrase somewhere safe." before the account exists (tag `login/cloud_create_confirm_ok`) |
| Cloud sign-in, wrong passphrase | Banner "That passphrase doesn't unlock this account's data." (distinct from bad credentials) |

## Screenshots

Committed flow set (`docs/screenshots/new-app/flows/`):

- `01-login-00-mode-picker.png` — brand block + mode picker (brief 04)
- `01-login-01-initial.png` — server URL step
- `01-login-02-cleartext-dialog.png` — `http://` cleartext warning
- `01-login-03-error-banner.png` — wrong-password banner
- `02-dashboard-01-idle.png` — dashboard after login

Multi-backend QA evidence: `docs/screenshots/new-app/qa-multi-backend/` (device child setup, device offline save, sign-out wipe copy, cloud create + warning, cloud wrong-passphrase).

## Deviations from spec

- The original spec §1.1 had a single login form; brief 04 (owner-approved) replaced it with the mode picker. All pinned server-flow behavior is preserved byte-for-byte behind `login/mode_server`.
