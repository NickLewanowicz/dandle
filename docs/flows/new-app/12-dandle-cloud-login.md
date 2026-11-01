---
title: "Flow verification: Dandle Cloud (encrypted sync)"
---

# 12 — Dandle Cloud (brief 05 §5) — QA flow doc

**Purpose:** prove the end-to-end-encrypted PocketBase backend: account create/sign-in with a separate encryption passphrase, child setup, event sync, and the two critical guarantees — the server stores only ciphertext, and a wrong passphrase is a distinct, recoverable error.

## How to verify

1. Fresh state: tap `login/mode_cloud` → lead line "Your data is encrypted before it leaves this phone."
2. **Create account** tab (`login/cloud_tab/create`): the muted warning "If you lose this passphrase, your data can't be recovered — not even by us." is always visible; mismatched confirm shows live "Passphrases don't match." and gates submit; submit raises the non-skippable confirm dialog "Save your passphrase somewhere safe." (`login/cloud_create_confirm_ok` proceeds).
3. Create: username + password + passphrase (twice) → `login/cloud_submit` → child setup ("Your baby", lead "Add your baby to start syncing.") for a fresh account → dashboard. The child is pushed to the cloud ENCRYPTED (record id = the local child id); the push is best-effort — an offline failure never dead-ends sign-in and the next sync carries it.
4. Save an event (e.g. bottle feeding, amount 42) → row syncs.
5. **Zero-knowledge check** (host-side): superuser-auth against `https://dandle.pockethost.io` (creds in `~/.config/dandle/pb-admin.env`; set a custom User-Agent — pockethost blocks python-urllib defaults) and read the user's `sync_records`: `type` and `client_updated` are plaintext (partial-encryption design, brief 05 §5.1), but `payload` must be base64 ciphertext with NO readable note/amount/child strings. The `users` record carries `wrapped_dek` + `enc_salt` + `enc_params`.
6. **Multi-device sign-in:** sign out → `login/mode_cloud` → Sign in tab → same username + password + passphrase → the timeline pulls down and decrypts (child + events present).
7. **Wrong passphrase:** sign out → sign in with correct username/password but a wrong passphrase → banner **"That passphrase doesn't unlock this account's data."** — distinct from "Wrong username or password." (bad credentials).
8. Cross-user isolation is pinned server-side by the collection rules (`user = @request.auth.id`, deleteRule null) — verified by the 2026-11-01 rules smoke (create/list/isolation/hard-delete-blocked/tombstone), see scripts/cloud/apply-schema.py.

Tags: `login/mode_cloud`, `login/cloud_tab/sign_in`, `login/cloud_tab/create`, `login/cloud_username`, `login/cloud_password`, `login/cloud_passphrase`, `login/cloud_passphrase_confirm`, `login/cloud_passphrase_warning`, `login/cloud_submit`, `login/cloud_create_confirm`, `login/cloud_create_confirm_ok`.
JVM tests: `CryptoTest` (HKDF RFC 5869 vector, Argon2id determinism, envelope round-trip, wrong-passphrase distinctness, AAD ciphertext-swap rejection, fresh-nonce), `LocalBackendTest` LWW/tombstone merge policy (shared merge path).

## Observed (2026-11-01, build on emulator-5554)

1. Live server smoke (rules): username identity auth ✓, record create ✓, own-list ✓, cross-user isolation ✓ (other account lists 0 records), hard DELETE rejected (tombstone-only) ✓, tombstone PATCH ✓.
2. Schema applied to pockethost 2026-11-01 by `scripts/cloud/apply-schema.py` (users: username identity + envelope fields + optional email; sync_records: zero-knowledge rules).
3. Emulator UI states verified: cloud landing copy, warning + live mismatch gating, non-dismissable create-confirm dialog ("Go back" keeps drafts) — qa-multi-backend screenshots.
4. **Full device E2E VERIFIED (QA 2026-11-01, fresh account qa-user-removed):** create → confirm dialog → child setup ("Add your baby to start syncing.") → child saved + pushed encrypted → identity adoption logged (`cloud child identity adopted: <uuid> -> <15char>`) → feeding synced → host-side check: both sync_records pure ciphertext, zero readable markers, both record ids `^[a-z0-9]{15}$` AND matching the local DB exactly → sign-out → sign-in: child MERGES ("QA Cloud Kid Two" in switcher, feeding "Amount: 42" restored, children table = 1 row) → wrong-passphrase banner distinct (auth 200 + local unwrap fail). One P0 found and fixed during verification (cloud network calls were main-dispatcher; now Dispatchers.IO), plus the pushChild AAD/id-binding bug (child identity now minted+sent+adopted). Screenshots: cloud-child-setup.png, cloud-dashboard.png, cloud-wrong-passphrase.png, cloud-signout-copy.png.

## Branches / states

| State | Result |
|---|---|
| Create, mismatched confirm | Live "Passphrases don't match.", submit gated |
| Create, confirm dialog | "Save your passphrase somewhere safe." — Go back keeps drafts |
| Create success | Child setup → dashboard (empty for new accounts) |
| Sign in, correct passphrase | Timeline pulls + decrypts |
| Sign in, wrong passphrase | "That passphrase doesn't unlock this account's data." |
| Sign in, wrong password | "Wrong username or password." (passphrase field clean) |
| Offline | "Couldn't reach Dandle Cloud — check your connection and try again." |

## Threat model (honest statement)

Protects event contents against the server/host/operator (zero-knowledge: server sees type, timing, device label, ciphertext). Does NOT protect a compromised unlocked device (Keystore + app sandbox are that boundary). Lost passphrase = data unrecoverable — stated at creation; recovery kit is a fast-follow brief. Timers are local-only on cloud in v1 (brief 05 D9).

## Screenshots

`docs/screenshots/new-app/qa-multi-backend/` — `cloud-create-account.png`, `cloud-warning.png`, `cloud-dashboard.png`, `cloud-wrong-passphrase.png`.

## Deviations from spec

- Password reset/email flows deliberately absent (owner decision D3: username + password + passphrase; no email dependency).
- Realtime SSE sync deferred (fast-follow).
