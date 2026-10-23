---
title: "Framework brief: React Native"
---

# Framework evaluation: React Native (Expo) for Dandle

*Authored by the React Native Expert persona, October 2026. Research current as of 2026-10-16; local toolchain verified on this machine (see §8). Read alongside the Kotlin/Compose and Flutter briefs.*

---

## 1. Executive summary

- **Verdict: React Native + Expo is a strong fit for THIS project, with two honest caveats (wallpaper dynamic color needs one companion lib; widgets ride on a single-maintainer library).**
- The #1 criterion — AI-agent QA — is where RN is genuinely strongest of the three candidates: `react-native-web` renders a **real DOM with a real accessibility tree**, so a Playwright-class agent QA's ~90% of UI flows in a browser in seconds per iteration, no emulator, no APK.
- The emulator loop still works well: RN renders real Android `View`s, so UiAutomator sees them; `testID` → resource-id and `accessibilityLabel` → content-desc give the QA agent stable selectors.
- The stack is boring-in-a-good-way in late 2026: Expo SDK 57 / RN 0.86 / React 19.2, New Architecture mandatory since RN 0.82, Hermes V1 default, official `expo-sqlite` + Drizzle, `expo-background-task` over WorkManager.
- Widgets are the weakest native story: `react-native-android-widget` (899 stars, active, Expo config plugin) declares layouts in JS over RemoteViews — works, but it is not Glance/Compose-first-class, and it **cannot be QA'd in a browser**.
- Against Kotlin: we lose native ceiling (startup, APK size, dynamic color in one line, WorkManager without a wrapper). Against Flutter: they beat us on render consistency; we beat them decisively on browser-QA (canvas-rendered Flutter web has no DOM to automate) and on TS training-data depth.
- Install burden on this Mac is near zero: Node 25, Android SDK, emulators, and Gradle cache already present; first Expo build is ~5–15 min, then the browser loop costs nothing.

---

## 2. 2026 state of the art

Everything we'd use, with versions as of October 2026:

| Piece | Status (Oct 2026) | Source |
|---|---|---|
| **Expo SDK** | SDK 57 (Jun 30, 2026) — RN 0.86, React 19.2. Positioned as a small, non-breaking update over SDK 56 (May 21, 2026, RN 0.85), which shipped ~16% faster iOS builds, ~40% faster Android cold starts, stable **Expo UI**, and a rebuilt Expo Router. | https://expo.dev/changelog/sdk-57, https://expo.dev/changelog/sdk-56 |
| **New Architecture** | Default since SDK 54 / RN 0.81 (Sep 2025). SDK 54 was the **last** release supporting Legacy Architecture; RN 0.82+ / SDK 55 require New Architecture (Fabric + TurboModules + bridgeless JSI). Nothing we'd use is legacy anymore. | https://expo.dev/changelog/sdk-54 |
| **Hermes** | Hermes V1 (released experimental in RN 0.82, **default in RN 0.84**): reported 25–55% faster JS execution, 15–40% lower memory, 10–30% faster cold start vs prior Hermes/JSC. Default engine in every Expo SDK we'd ship. | https://www.tothenew.com/blog/hermes-v1-by-default-in-react-native-0-84-the-biggest-performance-win-of-2026/, https://reactnative.dev/docs/hermes |
| **expo-sqlite + Drizzle** | `expo-sqlite` ships a synchronous native API on Android/iOS and a WASM build for web. Drizzle has an **official first-class driver** for Expo SQLite with Drizzle Kit migration generation and **live queries** (re-run on DB change) — a genuinely good offline-first story in 2026. | https://orm.drizzle.team/docs/sqlite/connect-expo-sqlite |
| **Background tasks** | `expo-background-task` wraps **WorkManager** on Android (15-minute minimum interval; OS may defer further under Doze/battery optimization). Community reports of unreliable firing exist; design implication in §4. | https://docs.expo.dev/versions/latest/sdk/background-task, https://www.reddit.com/r/expo/comments/1n0r0zj/expo_background_task_is_so_unreliable |
| **Home-screen widgets** | `react-native-android-widget`: JS/TS-declared layouts using RN-familiar components (`Box`, `Text`, `Image`, …) mapped to Android RemoteViews; ships an Expo config plugin; 899 stars, active master, Android-only. Official `expo-widgets` (SDK module) is **iOS-only** today. Callstack's **Voltra** is a newer entrant. | https://github.com/sAleksovski/react-native-android-widget, https://docs.expo.dev/versions/latest/sdk/widgets/ |
| **react-native-web** | First-class Expo target (`npx expo start --web` / `expo export --platform web`). Renders RN components to real DOM with ARIA mapping. Occasional version-pinch bugs exist (e.g., the SDK 54 + React 19 web-build break, since fixed by the normal upgrade path) — pin to SDK-blessed versions. | https://github.com/expo/expo/issues/40769, https://necolas.github.io/react-native-web/ |
| **UI library** | `react-native-paper` v5 = the only one of the three candidates shipping real **Material Design 3** components; `gluestack-ui` is design-system-agnostic; `uniwind` (NativeWind lineage) is Tailwind styling only — no components. Paper's dynamic-color story has a gap (see §4). | https://www.callstack.com/blog/react-native-paper-5-0, https://gluestack.io |

**Maturity summary:** every load-bearing dependency (router, storage, background task, UI kit) is either a first-party Expo module or a mainstream OSS library. The only truly third-party, single-team dependency is the widget library — and it maps exactly to our "two widgets" scope, not to an open-ended platform surface.

---

## 3. AI-agent QA ergonomics (the #1 criterion)

This is the section that decides the evaluation, so here is the concrete mechanics, not vibes.

### 3.1 Loop A — browser QA via react-native-web (the workhorse)

**Setup (once):** `npx expo start --web` serves the app on `localhost:8081` with Metro HMR. The QA agent (browser-use MCP / Playwright) opens that URL.

**What the agent actually sees:** `react-native-web` compiles RN primitives to real DOM elements. The mapping that matters for automation:

| RN prop | Web DOM | Playwright / browser-MCP handle |
|---|---|---|
| `accessibilityRole="button"` | `role="button"` | `getByRole("button", { name })` |
| `accessibilityLabel="Start feeding timer"` | `aria-label` | resolved by name lookup |
| `testID="quick-action-feeding"` | `data-testid` (and `id` with a small wrapper) | `getByTestId(...)` |
| `TextInput` | `<input>`/`<textarea>` | native typing, focus events |

This is a **real accessibility tree** — the agent gets the same snapshot quality it has when testing a plain web app. That is the single biggest QA-ergonomics win of the three frameworks: Flutter web draws to a canvas (no DOM nodes to target), and native emulator snapshots are coarser (§3.2).

**Timings, honestly:**
- Metro cold start: ~2–5 s. Hot reload after a source edit: **\<1 s**. 
- One agent iteration (snapshot → click → verify): **~1–3 s**.
- A full user flow (login → timeline → start timer → edit event → assert): **~10–30 s**.
- Determinism: **very high (~95%+)**. Flakes come almost entirely from animations — disable them on web (one `useReducedMotion`-style gate) and it gets boring.

**One honest wrinkle — CORS:** in the browser loop, `fetch` from `localhost:8081` to the self-hosted Baby Buddy at `localhost:8000` is cross-origin; Django won't send CORS headers by default. Native Android is immune (no browser same-origin policy). Fix for the QA loop only: a ~20-line dev proxy in this repo's `scripts/` (`/bb/* → localhost:8000`) used by the web build's API base URL. The native build keeps pointing at `10.0.2.2:8000`. This is dev-tooling, not product code.

**Second wrinkle — storage on web:** `expo-sqlite` has a WASM/web build (OPFS-backed; needs COOP/COEP headers on the static server, or run in-memory for QA). The sync-queue logic — the part the client cares most about — therefore **runs and is testable in the browser**, including against the real server through the proxy.

### 3.2 Loop B — emulator QA (the reality-check)

**Setup:** the existing `Pixel5` AVD (API 32, arm64) via the android-emulator MCP tools already on this machine.

**What the agent actually sees:** RN renders real `android.view.View`s, so UiAutomator *does* see them — this is a genuine structural advantage over Flutter (whose Semantics tree is not a View tree). Selector reliability:

| Target | Where it comes from | Reliability on RN Android |
|---|---|---|
| `text` | literal Text content | high |
| content-desc | `accessibilityLabel` | high — this is the workhorse |
| resource-id | `testID` | generally works (RN maps `testID` onto the view's test/accessibility id, which Appium/uiautomator read as `resource-id`), but this mapping has a long history of edge cases, and view flattening means it's only where you set it. Convention below fixes this. | 

Sources: https://www.testmuai.com/blog/react-native-best-practices, https://medium.com/@thevinaysingh/appium-locators-react-native-android-d954871a1e2, https://momentic.ai/blog/expo-react-native-end-to-end-testing.

**Project convention (non-negotiable, cheap):** every interactive element gets **both** `testID` and `accessibilityLabel`. Then Loop B selectors are as stable as Loop A's, and accessibility is a feature, not a tax. (Maestro-community guidance says the same: testID + label beats text matching. https://maestro.dev/insights/how-to-test-react-native-forms-with-maestro-studio)

**Timings, honestly (on this M-series Mac):**
- Emulator cold boot: 30–90 s (usually already warm for the MCP session: ~0).
- Incremental `assembleDebug`: **~15–40 s** after the first full build (~5–15 min, mostly Gradle downloads).
- Install + launch: ~5–10 s. Snapshot (`ui_describe`): ~0.5–2 s.
- One iteration (tap → snapshot → assert): **~3–8 s**. Full regression pass: 5–15 min.
- Determinism: **high but below Loop A (~85%)**. Typical flakes: keyboard focus timing, swipe velocity, edge-to-edge inset differences, server latency over `10.0.2.2`.

### 3.3 The two-loop strategy (concrete)

- **Loop A (browser) covers:** all six quick-action flows, timer toggle UI, timeline rendering/filtering, quick-edit forms, offline-queue state machine against the real server through the dev proxy, validation and error states, theming, i18n, empty states. ~90% of flows, ~1–3 s per iteration, zero native flake.
- **Loop B (emulator) covers what Loop A *cannot*:** APK install + first-run, login against cleartext `http://10.0.2.2:8000` from a real device network stack, both home-screen widgets on the launcher, WorkManager background flush timing, edge-to-edge/system-bar insets, back-gesture behavior, dynamic color, haptics, perf sanity on a real-ish device.
- **Handshake rule:** a flow is "done" only when it passes Loop A (spec coverage, fast) *and* its native-touching slice passes Loop B. Screenshots from both loops land in `docs/screenshots/` per the working agreement.

### 3.4 What can NEVER be QA'd in a browser

Said plainly: **home-screen widgets** (RemoteViews hosted by the launcher — no browser can render them), **WorkManager/Doze background execution**, **APK install/permissions/first-run**, **wallpaper-derived dynamic color**, **edge-to-edge insets and system back**, **haptics**, **foreground-service notification** (if we ever add one for running timers). Everything else in this app's scope is Loop-A-able.

---

## 4. Feature fit

### 4.1 Android home-screen widgets — the weakest native story, quantify the risk

`react-native-android-widget` (https://github.com/sAleksovski/react-native-android-widget, docs: https://saleksovski.github.io/react-native-android-widget):

- **How it works:** layouts are written in **JS/TS with RN-style components** (`Box`, `Text`, `Image`, `Stack`) which the library serializes into Android **RemoteViews**. Interactions are click-actions routed back into JS callbacks. A native `WidgetProvider` (added via the library's Expo config plugin) hosts the widget; rendering happens from the JS runtime.
- **Layout ownership:** we write it in JS — same language and (loosely) same component model as the app. No Kotlin required. That cuts both ways: it is *not* Compose/Glance, the RemoteViews subset is limited (no arbitrary custom drawing), and update latency is hundreds of ms while the JS runtime spins up.
- **Maturity/risk, honestly:** 899 stars, single primary maintainer, Android-only, active but small. This is the highest-supply-chain-risk dependency in the whole proposal. Mitigations: our two widgets (next-event glance + quick actions) are exactly the library's sweet spot; the JS layout layer isolates us (worst case: reimplement two RemoteViews layouts natively, ~200 LOC each); data flows in from our SQLite snapshot, not from the widget's own logic.
- **Alternative:** official `expo-widgets` exists but is **iOS-only today** (https://docs.expo.dev/versions/latest/sdk/widgets/) — it does not help the Android requirement, but it's the natural path if/when we ship iOS widgets later.

### 4.2 Offline queue with conflict surfacing

This is RN's strongest feature-fit area after browser QA:

- `expo-sqlite` (synchronous native API, no async-boundary bugs) + **Drizzle's official driver** gives typed schema, generated migrations bundled with the app, and **live queries** that re-run when tables change — the timeline and queue badges update themselves (https://orm.drizzle.team/docs/sqlite/connect-expo-sqlite).
- Queue design: `pending_ops` table (op type, entity, payload JSON, created_at, attempts, last_error) → UI renders source-of-truth from SQLite → flush opportunistically on app events/connectivity (`expo-network`) → **`expo-background-task` as the safety net for while-the-app-is-closed catches**, never the primary path.
- Honest caveat on the safety net: WorkManager's 15-minute minimum interval plus Doze deferral means background flush is "eventually, possibly much later" (https://docs.expo.dev/versions/latest/sdk/background-task). The original app's own UX (foreground sync + queue) matches this reality, so parity is achievable; the *unit-test surface* (queue serialization, conflict merge, retry/backoff) is pure TypeScript and is Loop-A/unit-testable.
- Conflict surfacing (server 409 / newer-server-version): pure data logic → local `conflict` row state → in-app resolution sheet. Same on any framework; in TS it is unit-testable without an emulator.

### 4.3 Material 3 fidelity

- `react-native-paper` v5 ships the MD3 component set (buttons, FABs, time pickers, bottom sheets, snackbars) with MD3 color roles and `MD3DynamicTheme` utilities to derive light/dark schemes from a **source color** (https://oss.callstack.com/react-native-paper/docs/guides/theming).
- **The honest gap:** wallpaper-derived **Material You dynamic color is not built in** — Paper issue #3315 tracks it (https://github.com/callstack/react-native-paper/issues/3315). The workaround is a small companion native lib such as `react-native-dynamic-theme` that reads the Android system palette and feeds Paper's theme (https://react-native-dynamic-theme.vercel.app). Compared to Compose's `dynamicDarkColorScheme(context)` (one line), that's one extra native dependency with real but small risk. Verdict: MD3 *look* = yes, immediately; MD3 *dynamic color* = yes, with +1 small lib. `gluestack-ui` and `uniwind` would leave us hand-building M3 semantics; Paper is the right call for this project.

### 4.4 Background timers

Tap-to-toggle timers run as JS state persisted into SQLite immediately (so process death loses nothing); the running-timer chip and timeline derive from DB, not from JS clock ticks. An app-closed ticking notification would need a foreground service — **out of parity scope** (the original app doesn't do it either) and honestly weaker in RN than Kotlin. If scope ever demands it, that's the one place I'd send the client to the Kotlin column.

### 4.5 Cleartext HTTP

Self-hosted Baby Buddy is frequently plain `http://` on a LAN (our dev server is `http://localhost:8000` / `10.0.2.2:8000`). RN on Android is fine with cleartext once allowed: set `usesCleartextTraffic` via `expo-build-properties` (or a scoped network-security-config via config plugin) — one declarative line, keeps the escape-hatch deep link working (https://docs.expo.dev/versions/latest/sdk/build-properties). No framework advantage or disadvantage here; it's a checkbox.

---

## 5. Performance & reliability

- **Startup:** Hermes V1 + New Architecture put RN cold start at roughly **1–2 s** on mid-range hardware in 2026, with RN 0.85/0.84-era claims of 10–30% faster cold starts and ~40% lower memory in some benchmarks (https://www.tothenew.com/blog/hermes-v1-by-default-in-react-native-0-84-the-biggest-performance-win-of-2026/, https://www.rapidnative.com/blogs/react-native-performance-optimization-2026-playbook). Native Compose will still beat us — likely ~0.5–1 s vs our 1–2 s on the same device. For an app a parent opens 8 times a day, that difference is perceptible but not painful; I won't pretend otherwise.
- **Runtime:** bridgeless New Architecture = synchronous layout, JSI direct calls; no bridge bottlenecks for an app like this. Six buttons and a list will never stress it.
- **Memory:** expect roughly 150–250 MB resident vs ~80–150 MB for an equivalent Compose app (Hermes V1 claims 15–40% reductions vs old Hermes, but a second runtime + JS heap is still there). On any phone sold this decade: irrelevant.
- **Where performance barely matters, honestly:** *everywhere in this project.* The heavy operations are one HTTP call to a LAN server and a SQLite insert. The perf section exists to be honest that Kotlin wins the numbers, and that the numbers do not decide this evaluation. The place perf *could* matter is widget-render latency via the JS runtime (§4.1) — hundreds of ms, acceptable for a glance widget.
- **Reliability:** OTA updates via `expo-updates` (JS fixes ship without a store round-trip) is a genuine self-hosted-friendly win; crash surfaces are TS-level, which our own AI team can triage fast. Known pain points: the SDK-upgrade treadmill (~3 SDKs/year) and the occasional library lagging a new RN (e.g., the SDK 54 web/React 19 break — https://github.com/expo/expo/issues/40769).

---

## 6. Cross-platform upside

- **iOS via Expo:** `npx expo prebuild` + local `xcodebuild` (present on this Mac) or **EAS Build** in the cloud. SDK 54+ ships **precompiled React Native for iOS**, which cut clean-build times dramatically, and SDK 56 added another ~16% (https://expo.dev/changelog/sdk-54, https://expo.dev/changelog/sdk-56).
- **Realistic "one codebase" story:** ~95% shared (all business logic, DB, sync, screens). The Android-specific parts (widgets via `react-native-android-widget`, cleartext config, `10.0.2.2` dev host) are already isolated behind the config-plugin/API layer. iOS widgets would come via the official iOS-only `expo-widgets` (https://docs.expo.dev/versions/latest/sdk/widgets/). Honest caveats: Paper's M3 feel is Android-first (it will look Material-ish on iOS, which for this app is acceptable); `expo-background-task` on iOS uses BGTaskScheduler with worse timing guarantees than WorkManager (https://docs.expo.dev/versions/latest/sdk/background-task).
- **Web as a fifth wheel:** we get the browser QA loop *for free* from the same codebase — and, if the client ever wants it, a rudimentary self-hosted web client is nearly a build flag away. Neither Kotlin nor Flutter can say that (Flutter web = canvas, fine to look at, useless to automate).

---

## 7. GLM-5.3 codegen fit

- **Training-data abundance:** TypeScript + React + RN is the largest mobile-dev corpus any model has seen; there are now public benchmarks scoring models specifically on RN tasks with real libraries (Reanimated, React Navigation, Zustand, React Query) (https://www.codesota.com/tasks/react-native-code-generation), and the practitioner ecosystem of Claude/Copilot RN workflows is deep (https://medium.com/cars24/claude-code-for-react-react-native-workflows-that-actually-move-the-needle-33b8bb410b14). For an AI-driven team, TS UI code is the highest-hit-rate target there is. Dart/Compose corpora are smaller.
- **Known failure mode #1 — version drift:** models confidently emit stale APIs (class components, react-navigation v5 idioms, `expo link`-era advice, SDK-49 config styles). Expo's tooling mechanically catches most of it: **`npx expo install` pins SDK-compatible versions**, `npx expo install --check` and `npx expo-doctor` flag mismatches, and `tsc --noEmit` + ESLint catch API drift at compile time. Our discipline: never hand-edit versions; always `expo install`.
- **Known failure mode #2 — native-module pitfalls:** models invent native-module wiring outside prebuild. Rule: every module with native bits goes in `app.json` plugins (config plugins), `android/`/`ios/` are generated (`prebuild`) and never hand-edited; that keeps the "it works on my machine" class of bug out.
- **Known failure mode #3 — Metro/config strangeness:** monorepos, symlinks, and custom resolvers are where models hallucinate Metro config. This project is a single-package app — the risky surface simply doesn't exist.
- **How QA catches the rest:** the Loop-A browser loop fails fast and legibly on UI regressions (real DOM assertions); the sync/queue logic is pure TS under vitest/jest; Loop B catches the native slice. The failure modes an AI *can't* see (type errors aside) are exactly the two loops' job — and Loop A is uniquely cheap here.

---

## 8. Toolchain on this machine (verified 2026-10-16, read-only)

Measured, not assumed:

| Check | Result |
|---|---|
| `which node npm bun` | `/opt/homebrew/bin/node`, `/opt/homebrew/bin/npm`, `~/.bun/bin/bun` |
| Versions | node **v25.8.1**, npm **11.11.0**, bun **1.1.30** |
| JDK | PATH = Homebrew **OpenJDK 25.0.1**; Android Studio bundled JBR = **JDK 21.0.9** (`/Applications/Android Studio.app/Contents/jbr`) |
| Android SDK | Complete: platforms 29–35, build-tools, NDK, emulator, platform-tools |
| AVDs | `Pixel5` (API 32 arm64 google_apis — the working-agreement device), `Pixel_3a_API_32_arm64` |
| Gradle cache | `~/.gradle` already 4.6 GB |
| Disk free | 83 GB |
| Existing scaffold | `app/` = AGP 8.7.3 / Kotlin 2.0.21 / Compose BOM 2024.12.01, single `:app` module, 1 Kotlin file (near-empty) — untouched by this proposal |

**Install burden estimate for Expo:**
1. `npx create-expo-app@latest` → ~600 MB–1 GB `node_modules`. Node 25 is non-LTS; Expo supports current LTS lines, and if Metro/Expo CLI grumble, `brew install node@22` is a one-command fallback. Bun exists but npm is the safe default with Metro.
2. **JDK note (the one real snag):** Expo SDK 56/57-era Gradle/AGP is happiest on JDK 17–21; Homebrew's JDK 25 on PATH may trip Gradle's version matrix. Fix is **zero-install**: point `JAVA_HOME` (or `org.gradle.java.home`) at the existing Android Studio JBR 21. The working agreement's "JDK 17" note aligns.
3. First `prebuild` + `assembleDebug`: ~5–15 min (Gradle downloads RN/Expo artifacts into the existing 4.6 GB cache; disk has plenty of headroom). Incremental debug builds thereafter: ~15–40 s.

**Iteration-loop speed vs the existing Gradle scaffold:** the scaffold's incremental build is comparably fast (~10–30 s), but *every* UI change costs an APK reinstall + launch + UiAutomator snapshot (~10–20 s end-to-end). RN adds Loop A on top: **\<1 s HMR with DOM assertions, no APK at all**, with Loop B available at roughly the scaffold's cost. For an AI QA agent running dozens of iterations per feature, that is a 5–10× difference in feedback throughput, which is exactly what the client optimized for.

---

## 9. Weaknesses vs Kotlin/Flutter

Where we lose, stated without spin:

**vs Kotlin/Compose (the incumbent here):**
- **Widgets:** Glance/RemoteViews with full platform API access vs a JS-serialized RemoteViews abstraction from a single-maintainer library. Kotlin wins; the client must accept medium supply-chain risk or a possible small native fallback later.
- **Material You dynamic color:** one line in Compose vs one extra native lib in RN (§4.3).
- **Startup/memory/APK size:** ~0.5–1 s / ~80–150 MB / ~5–8 MB vs ~1–2 s / ~150–250 MB / ~15–25 MB (RN's Android APK carries Hermes bytecode + the RN runtime).
- **Background correctness:** raw WorkManager + foreground services vs `expo-background-task`'s wrapper and JS-runtime wake-ups. For "flush my queue eventually" it's fine; for anything stricter, Kotlin wins.
- **Upgrade treadmill:** Expo ships ~3 SDKs/year and RN ~6 versions/year; Kotlin/AGP upgrades are slower and lazier. The client must budget for yearly churn (mitigated by Expo's tooling doing most of the work: https://expo.dev/changelog/sdk-57).

**vs Flutter:**
- **Render consistency:** Impeller gives Flutter pixel-identical Material everywhere; RN delegates to platform widgets (react-native-paper draws its own, but edge cases like text metrics and shadows differ slightly per platform). For a Material-3-on-Android-only app, this mostly evaporates — but it's real on iOS.
- **Startup/size numbers** are Flutter's favor too (AOT Dart, no JS runtime).
- **What we take back:** Flutter web is canvas — **no DOM, no accessibility-tree automation**; the client's #1 optimization (browser QA) is materially worse there. Flutter's UiAutomator story (Semantics nodes, no resource-ids by default) is weaker than RN's real-View rendering. Dart's training corpus is smaller for GLM-class models. And Flutter's official dynamic-color support is genuinely good — credit where due — it just doesn't tip a decision the QA loop dominates.

**What the client has to accept:** the widget library risk; one extra lib for dynamic color; ~10 MB extra APK; ~0.5–1 s slower start; the Expo upgrade cadence as a maintenance line-item. In exchange: the fastest AI-QA loop of the three candidates, the largest AI-codegen surface, and a real (if not immediately exercised) iOS path.

---

## 10. Implementation sketch

**Tooling:** Expo SDK 57 + Expo Router + TypeScript strict + react-native-paper v5 + expo-sqlite/drizzle-orm + expo-background-task + react-native-android-widget. Tests: vitest (logic) + Loop A (browser) + Loop B (emulator MCP).

**Package layout:**

```
Dandle-rn/
  app/                        # Expo Router routes
    _layout.tsx               # Paper theme provider + auth gate        (~120)
    (auth)/login.tsx          # server URL + token/login               (~150)
    (tabs)/
      timeline.tsx            # feed of events, filters                (~250)
      index.tsx               # dashboard: quick actions + run timers  (~200)
      settings.tsx            # server config, sync status, conflicts  (~180)
    event/[type]/new.tsx      # param-driven quick-action form         (~220)
    event/[id]/edit.tsx       # in-app quick edit                      (~200)
    conflict/[id].tsx         # conflict resolution surface            (~150)
  src/
    api/client.ts             # fetch + auth + error shapes            (~300)
    api/types.ts              # mirror docs/research/babybuddy-api.md  (~250)
    db/schema.ts              # drizzle tables: entities + pending_ops (~200)
    db/index.ts               # expo-sqlite driver + migrations        (~100)
    sync/queue.ts             # enqueue, flush, retry, conflict logic  (~450)
    sync/background.ts        # expo-background-task registration      (~80)
    features/                 # 6 quick actions + timer engine + forms (~2,400)
      timer.ts                # toggle, persist, derive state          (~300)
    ui/theme.ts               # Paper MD3 theme + dynamic color bridge (~150)
    ui/components.ts          # shared chips/cards/empty states        (~300)
  widgets/
    next-event.widget.tsx     # glance widget                          (~200)
    quick-actions.widget.tsx  # 4-6 button widget                      (~250)
  scripts/dev-proxy.mjs      # browser-QA CORS proxy (dev only)       (~40)
  e2e/                        # Loop-A assertions + fixtures           (~500)
```

**Estimated total: ~7,000–8,500 LOC TypeScript** for beta (parity + quick-edit + offline queue + 2 widgets). For calibration: the Kotlin/Compose build lands in a similar range with more of it non-shareable; roughly 20% of our LOC (sync, queue, forms) is unit-testable pure TS with no emulator in the loop.

**First three commits:**
1. **`chore: expo skeleton + QA loops online`** — `create-expo-app` (SDK 57), Expo Router shell, Paper MD3 theme, TypeScript strict, `testID`+`accessibilityLabel` lint rule, `scripts/dev-proxy.mjs`, web export working, first Playwright smoke test passing against `localhost:8081`, Loop B verified by `ui_describe` showing `resource-id`s on the dev screen.
2. **`feat: data layer — sqlite + drizzle + babybuddy client`** — schema + migrations, typed API client mirroring `docs/research/babybuddy-api.md`, token login against the local server (`10.0.2.2:8000` native / proxied web), vitest suite for request/response shapes and error mapping.
3. **`feat(timeline,diaper): first quick action end-to-end with offline queue`** — timeline list (drizzle live query), diaper change quick action, `pending_ops` enqueue + foreground flush + 409/conflict surfacing, QA'd in Loop A (full flow) and Loop B (airplane-mode + background flush), screenshots filed per the working agreement.

---

*Sources consulted (Oct 2026): [Expo SDK 57 changelog](https://expo.dev/changelog/sdk-57) · [SDK 56](https://expo.dev/changelog/sdk-56) · [SDK 54](https://expo.dev/changelog/sdk-54) · [Drizzle × Expo SQLite](https://orm.drizzle.team/docs/sqlite/connect-expo-sqlite) · [expo-background-task](https://docs.expo.dev/versions/latest/sdk/background-task) · [expo-widgets](https://docs.expo.dev/versions/latest/sdk/widgets/) · [react-native-android-widget](https://github.com/sAleksovski/react-native-android-widget) · [Paper MD3 theming](https://oss.callstack.com/react-native-paper/docs/guides/theming) · [Paper dynamic-color issue #3315](https://github.com/callstack/react-native-paper/issues/3315) · [react-native-dynamic-theme](https://react-native-dynamic-theme.vercel.app) · [Hermes V1 default in RN 0.84](https://www.tothenew.com/blog/hermes-v1-by-default-in-react-native-0-84-the-biggest-performance-win-of-2026/) · [RN perf playbook 2026](https://www.rapidnative.com/blogs/react-native-performance-optimization-2026-playbook) · [Hermes docs](https://reactnative.dev/docs/hermes) · [RN E2E without Appium/Detox](https://momentic.ai/blog/expo-react-native-end-to-end-testing) · [Playwright a11y testing](https://playwright.dev/docs/accessibility-testing) · [testID→resource-id mapping](https://medium.com/@thevinaysingh/appium-locators-react-native-android-d954871a1e2), [TestMu RN best practices](https://www.testmuai.com/blog/react-native-best-practices) · [Maestro Studio RN](https://maestro.dev/insights/how-to-test-react-native-forms-with-maestro-studio) · [expo web/React 19 issue](https://github.com/expo/expo/issues/40769) · [expo-build-properties](https://docs.expo.dev/versions/latest/sdk/build-properties) · [RN codegen benchmark](https://www.codesota.com/tasks/react-native-code-generation) · [Background-task reliability thread](https://www.reddit.com/r/expo/comments/1n0r0zj/expo_background_task_is_so_unreliable) · [gluestack](https://gluestack.io) · [Paper 5.0 announcement](https://www.callstack.com/blog/react-native-paper-5-0).*
