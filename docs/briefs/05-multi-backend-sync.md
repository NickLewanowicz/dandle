---
title: "Brief 05: Multi-backend sync — typed backend layer, on-device mode, encrypted Dandle Cloud"
---

> **STATUS: Implemented (2026-11-01)** — all decisions D1–D10 applied as recommended. Phases 2–3 committed separately (aa69d6e, 13cba6f); schema live on pockethost (rules smoke 2026-11-01); upgrade-path DataStore migration hardened after an emulator-found crash loop (same-name Preferences key). QA evidence in docs/flows/new-app/11+12.

# Brief 05 — Multi-Backend Sync

**Owner:** Maintainer → android-engineer + test-engineer · **Depends on:** Brief 04 (login picker UI), `docs/research/babybuddy-api.md`, `docs/briefs/00-architecture.md`, AGENTS.md #2/#4/#5.

## Goal

Decouple backend transport from application logic behind a **strongly-typed boundary** (the Kotlin equivalent of the requested "zod": kotlinx.serialization DTOs decoded at the adapter edge into domain models or a sealed error taxonomy — ViewModels and repositories never see Retrofit/HTTP types again), then support three backends:

1. **Baby Buddy** (self-hosted server) — current functionality, byte-for-byte behavior change: **none**.
2. **On-device** — net new: no server, no account; every event stays on this phone only.
3. **Dandle Cloud** (PocketBase at `https://dandle.pockethost.io/`) — net new: multi-device sync where records are **client-side encrypted** so the server operator (including the owner of the instance) cannot read event contents; users sign in with username + password + a separate **encryption passphrase** that works across devices.

## 1. Architecture — capability interfaces, not a fat gateway

Reviewed verdict: split into three small interfaces in a new `data/backend/` package; `domain/` stays pure (the Retrofit leak is entirely inside `data/` today — `ChildRepository.kt:10`, `EventRepository.kt:20`, `OutboxDrainer.kt:22`).

```kotlin
// data/backend/BackendError.kt — the typed boundary ("zod-equivalent")
sealed interface BackendError {
    data object Unreachable : BackendError               // IOException at the edge
    data object AuthExpired : BackendError               // maps onto existing session_expired pause
    data class ValidationError(val fieldErrors: Map<String, String>, val conflictRef: String?) : BackendError
    data object NotFound : BackendError
    data object Conflict : BackendError
    data class Http(val code: Int) : BackendError
    data object ServerError : BackendError
}

interface SyncPusher {   // transport for the outbox
    suspend fun pushCreate(type: EventType, draft: EventDraft): PushResult   // Ok(serverId) | Err(BackendError)
    suspend fun pushEdit(type: EventType, serverId: String, draft: EventDraft): PushResult
    suspend fun pushDelete(type: EventType, serverId: String): PushResult    // Deleted | AlreadyGone
}
interface PullGateway {  suspend fun pull(): PullResult; suspend fun listChildren(): ... }
interface TimerMirror? { start/stop/reconcile }          // BabyBuddy-only capability, nullable
```

- **BabyBuddy adapter** implements all three (mechanical extraction of the `when` blocks at `OutboxDrainer.kt:146–220` + the DRF 400-body parse and 403-message sniff `:307–313` — all DRF/BabyBuddy-isms stay inside the adapter).
- **Local adapter**: pusher completes inline minting `serverId = localUuid`; puller is an immediate no-op success; no timer mirror.
- **Cloud adapter**: pusher = encrypt → upsert; puller = fetch → decrypt → LWW merge; no timer mirror in v1 (timers stay local-only on cloud).
- **`OutboxDrainer` stays concrete** (mutex drain, oldest-first, 401-pause, NetworkDown ordering are backend-independent queue semantics); its constructor changes `apiProvider: () -> BabyBuddyApi` → `pusher: SyncPusher`. Strangler order keeps `OutboxDrainerTest` green at every step: (1) add `BackendError` + BabyBuddy error mapper behind existing behavior; (2) extract `BabyBuddySyncPusher` (code move); (3) neutralize outbox payloads to drafts **with** Room v2 (one identity change at a time); (4) extract `PullGateway`; (5) `AppContainer` selects implementations by active backend.

## 2. Room v2 — String identity + tombstones (one migration, never destructive)

Current types (verified): `EventEntity.serverId: Long?` (Entities.kt:43), `childId: Long` (:46), `ChildEntity.id: Long` pk (:24), `conflictServerId: Long?`, `TimerEntity.serverTimerId: Long?`; Baby Buddy pks are numeric, PocketBase ids are 15-char strings, on-device ids are UUIDs.

- **All remote/child identity columns → `String`** (serverId, conflictServerId, serverTimerId, `Child.id`, `EventEntity.childId`, `TimerEntity.childId`, `EventDraft.childId`, domain `Event.serverId`, `Session.activeChildId`, `serverLocalUuid(type, serverId)` in Mappers.kt:61, `EventDao.byServerId` Daos.kt:36). BabyBuddy numeric ids stringify at the DTO boundary; `decodeServerId` tolerates legacy numeric JSON while pre-migration queued ops drain. Alternative rejected (local autoincrement pk + serverChildId translation) — adds an identity-translation layer to every query for zero value.
- **Add `deleted: Boolean = false`** (tombstone for cloud LWW); keep `updatedAtMillis` as the LWW clock (it is already client-write time, EventRepository.kt:150) compared in **server-clock-adjusted** time (ServerClock exists). Every list/count DAO query gains `deleted = 0`.
- **Real `Migration(1, 2)`**: SQLite can't ALTER column types → standard table-rebuild (`CREATE _new`, `INSERT…SELECT` with `CAST(serverId AS TEXT)`, drop, rename). **Never destructive**: PENDING/FAILED rows are the user's only copy of offline captures. `MigrationTestHelper` test round-trips a PENDING row 1→2 (exportSchema already on).
- **Load-bearing invariant** ("synced ⇒ serverId != null" drives the CREATE-vs-EDIT branch, EventRepository.kt:153–165): LOCAL mode must mint `serverId = localUuid` at create, or every later edit silently becomes a duplicate CREATE — regression-test this.

## 3. Session & backend selection

- DataStore additions: `backend` enum (`BABYBUDDY | LOCAL | CLOUD`, default BABYBUDDY), `cloud_token`, cloud envelope JSON. `Session.isLoggedIn` becomes backend-aware (`LOCAL && initialized` counts) or LOCAL users can never leave the login screen (SessionStore.kt:27, MainActivity.kt:91). `active_child_id` gets a one-time Long→String pref migration.
- **`PassphraseVault`** (`data/crypto/` interface, Android Keystore impl in the android layer, fake in JVM tests): wraps the DEK at rest after first unlock so the passphrase isn't re-prompted every app open. data/ stays android-free.
- **Backend switching (v1): one install = one backend.** Switch = sign out with teeth: blocked while outbox non-empty or PENDING/FAILED rows exist (shows the exact unsynced count + explicit "discard N unsynced entries" override); `cancelUniqueWork("outbox-drain")` + drainer mutex held during wipe; reuses the existing sign-out clears; `backend` key written last. No row-scoping (every DAO query would grow a backend predicate for zero v1 value). Data migration between backends = explicit future brief.

## 4. On-device backend — where current code breaks (all must be fixed + tested)

1. `isLoggedIn` gate (above). 2. `DashboardViewModel.refresh()` unconditionally hits network (DashboardViewModel.kt:468–482) — LOCAL puller returns immediate success; LOCAL mode must have **no error path**. 3. `SyncWorker` carries `NetworkType.CONNECTED` (SyncWorker.kt:46–49) — an offline-only device defers the drain forever and the unsynced chip grows permanently; LOCAL drains inline, no WorkManager constraint. 4. serverId minting (above). 5. No child ⇒ every quick action silently no-ops (DashboardViewModel.kt:512 `?: return`) — the mandatory child-setup wizard (Brief 04) is the fix. 6. `webUrlFor` returns null (correct) but the long-press hint snackbar would teach a dead gesture — gate the hint on deep-link capability. 7. Sign-out in LOCAL mode = confirm-wipe copy ("This clears all data on this device."), not a server sentence.

> **Maintainer ruling to record (AGENTS.md #5):** the long-press-to-web escape hatch is a BabyBuddy-server feature; in LOCAL/CLOUD there is no web UI, so the long-press surfaces nothing. AGENTS.md gets a one-line amendment when Phase 4 ships — this is a spec change, noted here per the working agreement.

## 5. Dandle Cloud — PocketBase + end-to-end encryption

### 5.1 Server schema (pockethost instance, applied by `scripts/cloud/apply-schema.sh` via superuser REST; PB ≥0.30 confirmed live)

- **`users` (auth collection, exists — reconfigure):** identity = **username** (email optional/hidden — no email means no reset flow, which is honest: the passphrase is the real key). Added fields: `enc_salt` (text, b64), `enc_params` (text, JSON: algorithm, m, t, p, version), `wrapped_dek` (text, b64), `crypto_version` (number). Rules: owner-read-only.
- **`sync_records` (base collection):** `user` (relation→users, cascadeDelete), `type` (text: child|feeding|diaper|sleep|note|pumping|tummy_time), `payload` (text: b64 ciphertext), `client_updated` (number, epoch ms — client-owned LWW clock), `deleted` (bool, tombstone), `device` (text, label for debugging), `schema_v` (number). Server autodate `updated` = tie-breaker + incremental-pull cursor (`filter=(user='<id>' && updated>'<cursor>')` — percent-encode the quotes; MockWebServer test pins the encoding).
- **Rules (zero-knowledge):** list/view/patch/create: `user = @request.auth.id`; **deleteRule: null** (tombstones only; GC later via superuser). Server sees only: account name, record type, timing metadata, device label, ciphertext. **Partial-encryption disclosure (owner-accepted):** type + timestamps are plaintext so sync is seamless (delta pulls, LWW, tombstones) without downloading/decrypting everything; all domain content (child names, notes, amounts, times-of-day inside events) is ciphertext.

### 5.2 Crypto spec (client-side only)

- **KDF:** Argon2id (BouncyCastle `Argon2BytesGenerator`, lightweight API — **never** registered as a JCE provider) with versioned params starting m=64 MiB, t=3, p=1 → 32-byte key from the passphrase + 16-byte random salt. Params + version live **inside the envelope** (param changes can't brick old envelopes). Runs off-main-thread (~0.5–2 s) with the existing progress-in-button pattern.
- **Envelope:** random 32-byte DEK per account. `wrapped_dek = b64(nonce ‖ AES-256-GCM(DEK, key=Argon2(passphrase,salt), aad="dandle:v1:dek"))`. Wrong passphrase ⇒ GCM tag failure ⇒ exactly `WRONG_PASSPHRASE` (distinct from bad login). Passphrase change = unwrap + re-wrap only (no data re-encryption; future brief).
- **Records:** client mints the PocketBase record id (15-char `[a-z0-9]`) so AAD can bind identity: `payload = b64(nonce ‖ AES-256-GCM(json(event), key=HKDF-SHA256(DEK, info="dandle:v1:record"), aad="dandle:v1:sync_records:<id>"))`. Nonce = fresh random 96-bit per encryption. **AAD binding is the detail most likely to be wrong — dedicated test.** DEK at rest via `PassphraseVault` (Android Keystore).
- **Threat model (state honestly in docs):** protects against the server/host/operator reading event contents. Does NOT protect a compromised unlocked device (Keystore + app sandbox are the boundary there). Lost passphrase = data unrecoverable, full stop — copy says so at creation (Brief 04); recovery key = future brief.
- **Dependencies:** +`org.bouncycastle:bcprov-jdk18on` only. AES-GCM + HKDF (BC `HKDFBytesGenerator`, not hand-rolled RFC 5869) via javax.crypto — pure JVM ⇒ the whole crypto module is unit-testable in plain JVM (house test convention). Rejected: lazysodium-android (JNA natives break JVM tests), Tink (no Argon2). Crypto sits behind `PassphraseKdf`/`RecordCipher` interfaces (KMP hedge). R8/minify note: BC needs it in release builds.

### 5.3 PocketBase client — hand-rolled, no SDK

~300 lines of OkHttp in the LoginFlow.kt style (`auth-with-password`, record CRUD + filtered/sorted lists, `/api/batch` for push bursts, token refresh on 401 once then session_expired pause). Rejected: pocketbase-kotlin / pocketbase-kmp — Ktor-based (second HTTP engine, second logging story; the `BB/API` debug logging is load-bearing for QA), and low-maintenance third-party SDKs lag PocketBase 0.x. Contract tests recorded against the pinned PB version via MockWebServer; one opt-in live smoke test (`scripts/qa/cloud.sh smoke`) against pockethost. **Ops:** superuser credentials are provided out-of-band and stored in the owner's secret store — scripts read `PB_ADMIN_EMAIL`/`PB_ADMIN_PASSWORD` from env (`~/.config/dandle/pb-admin.env`), **never committed**. SFTP (`ftp.pockethost.io:2222`, key `~/.ssh/pockethost_ed25519`) is the documented fallback for `pb_migrations` file deploys. QA determinism: optional local PocketBase docker (`scripts/qa/cloud.sh local`) mirroring the live schema for offline emulator runs.

### 5.4 Sync protocol (cloud)

- **Push:** outbox drains through the cloud pusher (encrypt → create/patch `sync_records`; deletes = tombstone patch, hard delete never). Conflicts: server has no opinion (no plaintext to validate) — **LWW client-side**.
- **Pull:** on login (full) then incremental by `updated` cursor; decrypt; merge into Room by `serverLocalUuid` with LWW on `updatedAtMillis` (server-clock-adjusted), PENDING/FAILED local rows never overwritten (existing merge rule, EventRepository.kt:246–258); tombstones win over live rows with older clocks; UI purges acknowledged tombstones.
- **Cadence:** existing triggers unchanged (pull-to-refresh, sync-now chip, reconnect callback, post-write drain) — no realtime SSE in v1.
- Baby Buddy semantics are untouched: server-authoritative validation, conflict 400s surfacing with edit/delete resolution paths (AGENTS #4).

## 6. Phasing (each phase = green tests + QA + docs + commit; canaries green throughout)

| Phase | What | Key files (new ◆ / touched) | Gate |
|---|---|---|---|
| 1 | **Brief 04 Part A** rebrand + assets | ◆ `scripts/assets/gen-login-art.sh`, `drawable-nodpi/` art; LoginScreen, DashboardScreen, HelpAndAbout | capture-flows regen; flow doc 10 updated |
| 2 | Abstraction carve-out, BabyBuddy-only, zero behavior change | ◆ `data/backend/{BackendError,SyncPusher,BabyBuddySyncPusher,PullGateway,BabyBuddyPuller,TimerMirror}.kt`; OutboxDrainer, Child/Event/TimerRepository, ApiException (adapter-internal), AppContainer | `OutboxDrainerTest` untouched & green; new pusher test pins 400-conflict/401/403-sniff/404 |
| 3 | Room v2 String identity + tombstones + pref migration | Entities, Daos, Migrations ◆, Mappers, Models, SessionStore, OutboxPayloads (draft-based, legacy-tolerant), DashboardViewModel, QuickActionStore keys | MigrationTest ◆; full JVM suite green |
| 4 | On-device backend + picker with device+server cards (Brief 04 Part B minus cloud) | ◆ `data/backend/LocalBackend.kt`, `ui/child/CreateChildScreen.kt`; SessionStore (backend-aware isLoggedIn), MainActivity gate, AppContainer, SyncWorker (inline LOCAL drain), DashboardViewModel (hint gating), ChildRepository (local create) | LOCAL pusher tests (edit-after-synced = EDIT; unsent count stays 0 offline); flow doc 11; capture-flows picker step |
| 5 | Dandle Cloud | ◆ `data/crypto/{PassphraseKdf,RecordCipher,PassphraseVault}` + Keystore impl, `data/cloud/PocketBaseClient.kt`, `data/backend/CloudBackend.kt`, cloud login UI (Brief 04 §B), ◆ `scripts/cloud/apply-schema.sh`, ◆ `scripts/qa/cloud.sh` | RFC vectors + round-trip + AAD-tamper tests; PB contract tests; LWW merge tests; live smoke on pockethost; flow doc 12; cloud card appears on picker |

Fast-follow briefs (explicitly out of scope): passphrase change + recovery kit, realtime SSE sync, biometric unlock, cross-device timer sync on cloud, backend-to-backend data migration, multi-child add flow.

## 7. Top risks (ranked, with mitigations)

1. **Identity migration mid-refactor** (String entity + Long mapper ⇒ timeline duplicates on every pull via the `serverLocalUuid` match) → Phase 3 lands as one atomic change; migration round-trip test; merge test extended to String ids.
2. **Outbox contract drift during extraction** (403-sniff pause, 400→FAILED+conflict, 404-as-delete-success, stale-op drop are exactly what moves) → extraction-only commits; `OutboxDrainerTest` unchanged until rewire.
3. **Silent crypto design errors** (nonce reuse, missing AAD binding, unversioned envelope, LWW on skewed clocks) → RFC vectors, AAD-tamper test, version byte day one, server-clock-adjusted LWW.
4. **Backend-switch data loss** → drained-queue gate with explicit unsynced count; work cancel + drainer mutex held during wipe.
5. **PocketBase API drift on pockethost upgrades** → pinned contract tests + first-contact version check + live smoke in QA.

## 8. Test strategy (house rules: offline-path JVM test for every network behavior; bugs fixed on emulator get JVM + capture-flows assertions)

- Phase 2–3: existing suites stay green unmodified (they are the contract); new: pusher error-mapping, migration round-trip, String-id merge.
- Phase 4: LOCAL pusher semantics incl. the two sneaky breaks (WorkManager CONNECTED constraint offline; serverId-mint edit invariant).
- Phase 5: crypto (RFC 5869 HKDF vectors, Argon2 known-answer, GCM round-trip, wrong-passphrase ⇒ WRONG_PASSPHRASE, AAD/ciphertext-swap rejection), PocketBaseClient contract (filter encoding, auth refresh, batch), CloudBackend LWW merge + tombstones, login state machine (Brief 04).

## Owner decisions requested

| # | Decision | Recommendation |
|---|----------|----------------|
| D1 | Fat gateway vs capability split (`SyncPusher`/`PullGateway`/`TimerMirror?`) | Capability split (engineer-reviewed) |
| D2 | String identity everywhere + tombstone column, real migration | Yes — never destructive |
| D3 | Cloud account identity | username + password, no email |
| D4 | Partial encryption: type + timestamps + device label plaintext to the server | Accept (enables seamless delta sync; matches owner's "partial encryption is fine") |
| D5 | No passphrase recovery in v1 (lost passphrase = lost data; warning copy at creation) | Accept; recovery kit = fast-follow |
| D6 | v1 backend switching = drain-gated confirm-wipe (no parallel backends, no migration) | Accept |
| D7 | Crypto stack: BouncyCastle (Argon2id, HKDF) + javax.crypto AES-256-GCM, interfaces for KMP | Accept |
| D8 | PocketBase client hand-rolled on OkHttp (no SDK dependency) | Accept |
| D9 | Timers local-only on cloud backend in v1 | Accept |
| D10 | AGENTS.md #5 amendment: escape hatch is server-backend-only | Accept with Phase 4 |
