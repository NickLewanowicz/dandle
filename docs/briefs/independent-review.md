---
title: "Independent framework review"
---

# Independent review — 2026-10-16

Fresh-eyes audit of the plan + the login dead-end. Verdicts are evidence-backed; file/line refs are absolute.

---

## Deliverable 1 — Plan audit

**Verdict: the architecture is proportionate; it is NOT why login failed.** Six-button offline-first client landed at ~7.0k lines of app code + 2.8k test lines, single module, manual DI, two destinations. The briefs (00/01), spec, and DECISION are disciplined and the toolchain pins are justified. The login dead-end was one composable-scoping mistake plus a missing observability layer — not over-engineering. Specifics:

**(a) Simplify for beta (cost/benefit, not taste):**
1. **Server-clock offset persistence — the one real design smell.** `ServerClockInterceptor` (data/auth/Interceptors.kt) calls `offsetListener` on *every* response → `sessionStore.setServerOffsetMs` → a DataStore write *per HTTP response*, forever (every drain, every refresh). It bought seconds-level clock skew correction for a home LAN server, and it caused a sessionStore emission per response — the recomposition storm that triggered the login bug. SIMPLIFY: persist only when `|new−old| > 30s` (or once per app session). Keep `ServerClock` itself; entry stamping needs it.
2. **Debug logging: promised, never built.** RUNBOOK/AGENTS.md say debug builds log all network calls under `BB/*` tags. There is **zero** `Log.*` in the codebase and no `HttpLoggingInterceptor` in AppContainer.kt. Either add it (30 min: logging interceptor on both debug clients + a few lifecycle logs) or fix the runbook — as-is, QA is blind and draws wrong conclusions (see Deliverable 2).
3. **KMP-readiness constraints** (okio.Path DataStore ctor, android-free domain/data): cost is small and already paid; freeze here — no further abstraction until an iOS trigger actually fires (DECISION §4 ranks iOS last).

**(b) Local-first/outbox for parity scope: KEEP.** It is the deliberate differentiator (spec Feature 1b), already built and 133-tests green; deleting working tested code post-hoc is the over-reaction, not the plan. The session-expired pause/resume (401 flag) is speculative for a self-hosted key but already done — keep, don't extend.

**(c) Login approach: KEEP the form dance for beta.** GET /login/ csrf → POST form → session → /api/profile → api_key matches the original app (parity, per brief 01 §3), asks the user only for what they know, and is now proven end-to-end against the real server (Deliverable 2). Alternatives: **paste-API-key** (simplest possible, one field — good "advanced" fallback for reverse-proxy setups where form login breaks; cheap add later), **QR** (`/user/add-device/`, `BABYBUDDY-LOGIN:` JSON — best UX; needs camera+scanner; DEFER post-beta, and the main NSC comment already anticipates it). The bug was in the UI layer, not this choice.

**KEEP as-is:** two-destination nav, manual `AppContainer`, Room+outbox+WorkManager, LoginFlow, version pins/AGP-9 workaround, spec/brief discipline, scripts/qa. **SIMPLIFY:** clock-offset write frequency; add the promised debug logging; (already done, uncommitted) back-stack-scoped LoginViewModel. **DEFER:** QR login, KMP, anything past spec.

---

## Deliverable 2 — Login dead-end: root cause (confirmed, not hypothetical)

**Hypothesis #1 (proven): the LoginViewModel is swapped mid-login by a recomposition, orphaning the in-flight coroutine and its `SignedIn` event.** Chain in HEAD:

1. `MainActivity.RootNavigation` collects `sessionStore.sessionFlow` and constructs `LoginViewModel(...)` **inline, on every recomposition** (old MainActivity.kt, since removed in the uncommitted fix).
2. `ServerClockInterceptor` writes `serverOffsetMs` to DataStore on **every server response** — including the first response of the login attempt itself.
3. So: tap Continue → `doLogin()` → GET /login/ responds (~10 ms) → sessionStore emission → RootNavigation recomposes → **new** LoginViewModel → the screen's state collector re-binds to the fresh idle VM (**button back to idle**, no banner, no spinner), while the real login completes on the orphaned VM; its `SignedIn` is emitted on a `SharedFlow` with no replay and no live receiver → **no navigation**. Every later tap repeats the cycle.

**Evidence (all read-only):**
- nginx access log in the container (`/config/log/nginx/access.log`): **five complete successful dances** from `okhttp/5.4.0` (16:31–16:46 −0700), each `GET /login/ 200 → POST /login/ 302 → GET /api/profile 200 → GET /api/children/?limit=1 200`. **The "no request ever reaches the server" claim is false.**
- Device DataStore (`run-as net.babybuddy.next.debug cat files/session.preferences_pb`): real `api_key` + `server_url=http://10.0.2.2:8000` + `username=admin`, `session_expired` false — the dance ran to the last step and persisted. Relaunching the app right now should land on the dashboard (`startDestination = isLoggedIn`) — a 10-second proof the server side always worked.
- uiautomator dump: only `login*` tags on screen.
- "No logcat output at all" is **expected**: the app has zero `Log.*` calls and no OkHttp logging (RUNBOOK's `BB/*` is fiction). Crash buffer clean, no ANR, process stable 19 min, RenderThread ticking — nothing system-level is wrong.

**Hypothesis #2 (ruled out):** DataStore/`runBlocking` deadlock, broken dispatchers, NSC/`.debug` interaction, stale `canSubmit` snapshot. No `runBlocking`/`Thread.sleep` in main source; auth interceptor reads `@Volatile` mirrors (never blocks); one NSC with `cleartextTrafficPermitted=true` in main **and** debug (no suffix interaction); prefills and state updates work, so `viewModelScope`/main are healthy. `while (true)` at DashboardScreen.kt:75 is the harmless 1 s ticker.

**Fix status:** the uncommitted changes (Navigation.kt scopes the VM to the back-stack entry via `viewModel {}`; `LoginViewModelRetentionTest` pins it) are the **correct shape**. Verified: `./gradlew :app:testDebugUnitTest --tests '*Login*'` → 10/10 green including the new Robolectric test; and a host-side replay of the exact dance (csrf → form POST → 302 → profile) against `http://localhost:8000` returns the api_key — server semantics are sound.

**After committing the fix, do two things:** (1) commit it *with* the logging interceptor so the next "silent" bug is visible in logcat; (2) throttle the clock-offset write (Deliverable 1 §a1) — it is the same recomposition-per-response storm waiting to bite another screen.
