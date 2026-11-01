---
title: "Flow verification: Dandle Cloud (encrypted sync)"
---

# 12 — Dandle Cloud (brief 05 §5) — QA flow doc

**Purpose:** prove the end-to-end-encrypted PocketBase backend: account create/sign-in with a separate encryption passphrase, child setup, event sync, and the two critical guarantees — the server stores only ciphertext, and a wrong passphrase is a distinct, recoverable error.

## How to verify

1. Fresh state: tap `login/mode_cloud` → lead line "Your data is encrypted before it leaves this phone." with the muted service notice underneath (brief 06 §1): "Dandle Cloud is a free service and could change or end. Your data stays yours — it's encrypted with your passphrase and readable even without us." (`login/cloud_service_notice`).
2. **Create account** tab (`login/cloud_tab/create`, brief 06 rework): Username → "Email (optional)" (`login/contact_email`, supporting "Only used to warn you before big service changes. Never marketing."; a non-blank value must contain "@", live error "That doesn't look like an email.") → a single Password field with a Show/Hide toggle (brief 06 amendment: NO adjacent confirm — NIST 800-63B + web.dev/NN/g endorse the toggle; password managers autofill both fields identically so the pair validates nothing) → a separated **Encryption passphrase** section (`login/cloud_passphrase_generate`) where the field arrives PRE-FILLED with a generated passphrase, READABLE, with Regenerate (`login/cloud_passphrase_regen`) and "Create my own" (`login/cloud_passphrase_custom` — clears to an editable obfuscated field, minimum 8 chars). The muted loss warning ("If you lose this passphrase, your data can't be recovered — not even by us.") is always visible. Continue (`login/cloud_passphrase_continue`) → **"Create your account · step 2 of 2"**: paste the passphrase (`login/cloud_passphrase_verify`); a wrong paste shows live "That doesn't match — paste it again." and gates submit; the matching paste enables `login/cloud_submit`. The account is created ONLY on this second page, after the successful re-input — never on the generate step (evidence: cloud-verify-step.png shows step 2 of 2 holding an empty verify field with submit disabled). "Back" returns to the generate step with the passphrase kept. (The old confirm-passphrase field and the non-skippable "Save your passphrase somewhere safe." dialog are REMOVED — the two-step flow replaces them.)
3. Create: correct paste → `login/cloud_submit` → child setup ("Your baby", lead "Add your baby to start syncing.") for a fresh account → dashboard. The child is pushed to the cloud ENCRYPTED (record id = the local child id); the push is best-effort — an offline failure never dead-ends sign-in and the next sync carries it. The contact email, when typed, rides createUser as `contact_email` (omitted when blank).
4. Save an event (e.g. bottle feeding, amount 42) → row syncs.
5. **Zero-knowledge check** (host-side): superuser-auth against `https://dandle.pockethost.io` (creds in `~/.config/dandle/pb-admin.env`; set a custom User-Agent — pockethost blocks python-urllib defaults) and read the user's `sync_records`: `type` and `client_updated` are plaintext (partial-encryption design, brief 05 §5.1), but `payload` must be base64 ciphertext with NO readable note/amount/child strings. The `users` record carries `wrapped_dek` + `enc_salt` + `enc_params` (and `contact_email` only if the user typed one).
6. **Multi-device sign-in:** sign out → `login/mode_cloud` → Sign in tab (unchanged one-step: username + password + passphrase) → the timeline pulls down and decrypts (child + events present).
7. **Wrong passphrase:** sign out → sign in with correct username/password but a wrong passphrase → banner **"That passphrase doesn't unlock this account's data."** — distinct from "Wrong username or password." (bad credentials).
8. Cross-user isolation is pinned server-side by the collection rules (`user = @request.auth.id`, deleteRule null) — verified by the 2026-11-01 rules smoke (create/list/isolation/hard-delete-blocked/tombstone), see scripts/cloud/apply-schema.py.

Tags: `login/mode_cloud`, `login/cloud_tab/sign_in`, `login/cloud_tab/create`, `login/cloud_username`, `login/contact_email`, `login/cloud_password`, `login/cloud_passphrase_generate`, `login/cloud_passphrase`, `login/cloud_passphrase_regen`, `login/cloud_passphrase_custom`, `login/cloud_passphrase_continue`, `login/cloud_passphrase_verify`, `login/cloud_passphrase_back`, `login/cloud_passphrase_warning`, `login/cloud_submit`, `login/cloud_service_notice`.
JVM tests: `PassphraseGeneratorTest` (wordlist hygiene + entropy ≥ 60 bits: 6 words × log2(512) + log2(100) = 60.64), `LoginModePickerTest` (GENERATE→VERIFY machine: pre-fill, wrong-paste re-error, correct paste → signUp with passphrase + contact email, create-my-own, regenerate, back-from-verify, sign-in one-step, password-confirm gate), `CryptoTest` (HKDF RFC 5869 vector, Argon2id determinism, envelope round-trip, wrong-passphrase distinctness, AAD ciphertext-swap rejection, fresh-nonce), `CloudBackendSignUpMappingTest` (`contact_email` rides createUser only when provided), `LocalBackendTest` LWW/tombstone merge policy (shared merge path).

## Observed (2026-11-01, build on emulator-5554)

> Historical: observations 3-4 below captured the PRE-brief-06 create flow (confirm dialog). The sync/crypto/server observations remain valid; the UI create flow needs re-verification per "How to verify" above.

1. Live server smoke (rules): username identity auth ✓, record create ✓, own-list ✓, cross-user isolation ✓ (other account lists 0 records), hard DELETE rejected (tombstone-only) ✓, tombstone PATCH ✓.
2. Schema applied to pockethost 2026-11-01 by `scripts/cloud/apply-schema.py` (users: username identity + envelope fields + optional email; sync_records: zero-knowledge rules).
3. Emulator UI states verified: cloud landing copy, warning + live mismatch gating, non-dismissable create-confirm dialog ("Go back" keeps drafts) — qa-multi-backend screenshots.
4. **Full device E2E VERIFIED (QA 2026-11-01, fresh account qa-user-removed):** create → confirm dialog → child setup ("Add your baby to start syncing.") → child saved + pushed encrypted → identity adoption logged (`cloud child identity adopted: <uuid> -> <15char>`) → feeding synced → host-side check: both sync_records pure ciphertext, zero readable markers, both record ids `^[a-z0-9]{15}$` AND matching the local DB exactly → sign-out → sign-in: child MERGES ("QA Cloud Kid Two" in switcher, feeding "Amount: 42" restored, children table = 1 row) → wrong-passphrase banner distinct (auth 200 + local unwrap fail). One P0 found and fixed during verification (cloud network calls were main-dispatcher; now Dispatchers.IO), plus the pushChild AAD/id-binding bug (child identity now minted+sent+adopted). Screenshots: cloud-child-setup.png, cloud-dashboard.png, cloud-wrong-passphrase.png, cloud-signout-copy.png.

## Branches / states

| State | Result |
|---|---|
| Create, wrong paste on step 2 of 2 | Live "That doesn't match — paste it again.", submit gated |
| Create, "Create my own" | Field clears to editable + obfuscated; minimum 8 chars gates Continue |
| Create, matching paste | Create account enabled → child setup → dashboard (empty for new accounts) |
| Create, Back on step 2 | Returns to the generate step, passphrase value kept |
| Create, invalid email | "That doesn't look like an email.", submit gated |
| Sign in, correct passphrase | Timeline pulls + decrypts |
| Sign in, wrong passphrase | "That passphrase doesn't unlock this account's data." |
| Sign in, wrong password | "Wrong username or password." (passphrase field clean) |
| Offline | "Couldn't reach Dandle Cloud — check your connection and try again." |

## Threat model (honest statement)

Protects event contents against the server/host/operator (zero-knowledge: server sees type, timing, device label, ciphertext). Does NOT protect a compromised unlocked device (Keystore + app sandbox are that boundary). Lost passphrase = data unrecoverable — stated in-app at creation (muted warning) and enforced by the paste-to-verify proof-of-backup (brief 06 §4); recovery kit is a fast-follow brief. Timers are local-only on cloud in v1 (brief 05 D9). The service itself may change or end — disclosed on the entry screen; the data stays decryptable without us (brief 06 §1). `contact_email` is stored in cleartext by design (it is contact metadata; the copy discloses the use).

## Screenshots

`docs/screenshots/new-app/qa-multi-backend/` — `cloud-create-account.png`, `cloud-warning.png`, `cloud-dashboard.png`, `cloud-wrong-passphrase.png` (captured against the brief 05 flow). **Brief 06 rework: screenshots + `capture-flows.sh` re-capture PENDING the next QA round.**

## Deviations from spec

- Password reset/email verification flows deliberately absent (owner decision D3: username + password + passphrase; the brief 06 contact email is contact metadata only — no identity coupling, no reset promises).
- Passphrase generator: 6 words from a curated 512-word list + 2-digit suffix (60.64 bits) — the instruction sketch's "5 words × 128–256-word list + 2 digits" maxes out at 46.6 bits, under the same brief's ≥ 60-bit mandate; see `PassphraseGenerator` KDoc.
- Realtime SSE sync deferred (fast-follow).
