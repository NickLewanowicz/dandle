---
title: "Framework brief: Kotlin + Compose"
---

# Framework evaluation: Native Kotlin + Jetpack Compose

*Candidate brief by the Native Kotlin Expert persona. Date: 2026-10-16. Companion briefs: Flutter, React Native/Expo. All version claims checked against release pages on this date; local claims verified by running the scaffold at `app/`.*

## 1. Executive summary

**Verdict: recommend native Kotlin + Compose for Dandle.** Two home-screen widgets, background sync with conflict surfacing, Material 3 (incl. dynamic color), and a foreground-friendly timer are exactly the workload where native wins and where Flutter/RN shell out to native code anyway. The stack is fully stable in late 2026 (Compose 1.12.1, M3 1.4, Kotlin 2.4.21, Glance 1.2.0, Room 2.8.5). On the client's #1 criterion — AI-agent QA — the honest score: the belief "browser QA is easier for agents" is **true for Flutter/RN and not available to us**; Compose has no browser path that tests the real UI. But the APK+emulator loop I measured on this Mac is ~10 s warm end-to-end (6 s incremental build, 0.3 s install, instant launch) with a clean, deterministic accessibility tree, and the semantics system (`testTag` + `testTagsAsResourceId`) gives agents *better* targeting than un-annotated web DOM. You accept: no browser QA surface, an Android-SDK-shaped toolchain, and a weaker sub-second hot-reload story than Flutter. Widgets cannot be QA'd in a browser at all, which shrinks the gap further.

## 2. 2026 state of the art

Everything we would use is on a stable channel — no betas required for any feature in scope.

| Component | Current stable (Oct 2026) | Notes | Source |
|---|---|---|---|
| Kotlin | 2.4.21 (2026-10-08) | K2 compiler standard since 2.0; 2.5.0 planned Dec 2026 | [kotlinlang.org/docs/releases.html](https://kotlinlang.org/docs/releases.html) |
| Jetpack Compose (runtime/ui/foundation) | 1.12.1 (2026-10-07); 1.13.0-beta01 in flight | December '25 release was 1.10; roughly quarterly stable cadence | [developer.android.com/jetpack/androidx/releases/compose](https://developer.android.com/jetpack/androidx/releases/compose) |
| Compose Material 3 | 1.4.0 stable; 1.5.0-beta01 next | M3 "Expressive" components (button groups, loading indicator, flexible typography, motion) stable since the Dec '25 release | [compose-material3 releases](https://developer.android.com/jetpack/androidx/releases/compose-material3), [Dec '25 blog](https://android-developers.googleblog.com/2025/12/whats-new-in-jetpack-compose-december.html) |
| Room | 2.8.5 (2026-09-09); `androidx.room3` 3.x line exists | KMP since 2.7.0 (Apr 2025); minSdk 23+ (we're 29) | [room releases](https://developer.android.com/jetpack/androidx/releases/room) |
| DataStore | 1.2.1 (2026-03-11) | KMP since 1.1.0; preferences artifact for session/settings | [datastore releases](https://developer.android.com/jetpack/androidx/releases/datastore) |
| WorkManager | 2.12.0 (2026-09-23) | For offline-queue replay + widget refresh | [work releases](https://developer.android.com/jetpack/androidx/releases/work) |
| Glance (widgets) | 1.2.0 (2026-08-26); 1.3.0-alpha02 next | Stable; Compose-style API → RemoteViews | [glance releases](https://developer.android.com/jetpack/androidx/releases/glance) |
| Retrofit | 3.0.0 (May 2025), fully Kotlin | Built-in `converter-kotlinx-serialization` — no third-party converter needed | [proandroiddev Retrofit 3 guide](https://proandroiddev.com/retrofit-3-0-0-detailed-migration-guide-0d2c043d43e3) |
| OkHttp | 5.5.0 (2026-08-16); 5.0.0 stable since 2025-07-02 | Separate `okhttp-android` artifact | [okhttp CHANGELOG](https://github.com/square/okhttp/blob/master/CHANGELOG.md) |
| kotlinx.serialization | 1.11 stable; 1.12-RC (2026-09-04, Kotlin 2.4.10-based) | First-class Retrofit support | [kotlinx.serialization releases](https://github.com/Kotlin/kotlinx.serialization/releases) |

**KMP note:** Room, DataStore, kotlinx-serialization, and kotlinx-datetime are all KMP-stable. Compose Multiplatform for **iOS** has been stable and production-ready since **1.8.0 (May 2025)**; current line 1.9.3 (July 2026) tracks Jetpack Compose APIs ([JetBrains blog](https://blog.jetbrains.com/kotlin/2025/05/compose-multiplatform-1-8-0-released-compose-multiplatform-for-ios-is-stable-and-production-ready), [CMP 1.9 release notes](https://kotlinlang.org/docs/multiplatform/whats-new-compose-190.html)). **Retrofit/OkHttp are the notable exception** — JVM-only; an iOS target would swap in Ktor 3.x for the client transport (interface stays the same). Compose Multiplatform for **web** (Kotlin/Wasm, Skia canvas) is **Beta** since CMP 1.9.0 (Sept 2025) — not a production path and irrelevant below ([CMP 1.9 blog](https://blog.jetbrains.com/kotlin/2025/09/compose-multiplatform-1-9-0-compose-for-web-beta)).

## 3. AI-agent QA ergonomics (the #1 criterion)

**The exact loop an agent uses (measured on this machine today, warm caches, emulator already booted):**

```
$ export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
$ ./gradlew assembleDebug          # no-op: 2.3 s · one-file change: ~6.1 s (measured)
$ adb install -r app/build/outputs/apk/debug/app-debug.apk   # 0.3 s (streamed install)
$ adb shell am start -n app.dandle/.MainActivity     # ~0.1 s
$ <MCP> ui_describe                # ~1 s; accessibility tree as JSON
```

**Warm edit→verify cycle: under 10 seconds.** Cold start (fresh Gradle daemon, first build of the day) adds roughly 30–60 s; dependency downloads on first setup add minutes once. The `android-emulator` MCP plugin in this repo (ui_describe/ui_resolve/ui_tap/ui_type_text + screenshot + logcat) is precisely this loop and I exercised it end-to-end today: build → install → launch → drive.

**What the accessibility tree actually looks like (verified first-hand via `ui_describe` on the running Compose scaffold):** Compose content appears under `androidx.compose.ui.platform.ComposeView` as `android.view.View` nodes; a `Text` surfaces as `android.widget.TextView` with its `text`; `contentDescription` surfaces verbatim (the scaffold's "incrementButton"/"countLabel" were both resolved and tappable); a Compose `Button` maps to class `android.widget.Button` with `clickable=true`. Three things matter for the QA agent:

1. **`testTag` is invisible to UiAutomator by default.** UiAutomator matches resource-ids; Compose only publishes tags as resource-ids when you set `Modifier.semantics { testTagsAsResourceId = true }` — one line at the app root, conventionally debug-builds-only ([Semantics doc](https://developer.android.com/develop/ui/compose/accessibility/semantics)). **We will do this from commit 1** with a naming scheme (`login/server_url`, `dashboard/action_feed`), giving the agent stable `resourceId` targets in addition to text/description matching.
2. **`mergeDescendants` is a real pitfall, mostly benign for us.** `Button`/`ListItem` merge child semantics into one node, so inner `Text` may not be independently targetable in the merged tree; `useUnmergedTree` exists in Compose tests, and for on-device automation the practical fix is: put explicit `contentDescription`/`stateDescription` on the interactive node (as the scaffold already does). List rows that merged when they shouldn't are visible immediately in `ui_describe` output — this class of bug is *observable*, which is what matters for agent QA.
3. **Determinism is good.** The tree is stable across runs; text/description/role matching is reliable. Standard emulator hygiene applies and is scriptable: `adb shell settings put global window_animation_scale 0` (and `transition_animation_scale`, `animator_duration_scale`) to remove animation flakes; `adb shell pm clear app.dandle` for fresh-install tests; `adb shell cmd connectivity airplane-mode enable` for offline-queue tests — all things the QA persona's brief already assumes.

**Unit-test story — mostly JVM, no emulator.** Domain logic (elapsed-time math, queue replay/conflict resolution, date parsing) is pure Kotlin and runs on the JVM in milliseconds. Room DAOs run on JVM under Robolectric (standard practice, seconds per suite). ViewModel tests use `kotlinx-coroutines-test` + Turbine on the JVM. Only Compose UI interaction tests and screenshots need the emulator (or Robolectric-with-Compose, which works but is slower). For this app I'd expect roughly 80% of tests JVM-only.

**Emulator dependency — honest assessment:** required for anything UI-shaped; there is no `--headless` Compose UI mode. Boot cost is amortized (the emulator here is already running and stays warm; snapshot fast-boot makes cold boots ~30 s). This is the real tax vs Flutter/RN's browser mode, and I don't pretend otherwise.

**Browser-based QA for Compose: no, and precision matters.** There are two unrelated "Compose for web" things ([JetBrains Compose Multiplatform repo](https://github.com/jetbrains/compose-multiplatform)):
- **Compose Multiplatform for Web** (Kotlin/Wasm, the strategic one, Beta) renders to a **Skia canvas** — the DOM contains one `<canvas>` element. A browser-driving agent sees *nothing*: no buttons, no labels, no roles. Worse than useless for accessibility-tree QA.
- **Compose HTML** (Kotlin/JS DOM library) emits real HTML an agent *could* drive via Playwright — but it shares **zero UI components** with Compose Android (only the runtime). "Testing the app in a browser" would mean writing and maintaining a second, different UI. It is not a QA path for the real app.

So the client's belief is directionally right — Flutter web and RN web genuinely hand agents a DOM — but for *this* app the browser can't exercise widgets, WorkManager sync, background timers, or dynamic color regardless of framework, and the native loop above is fast enough that the gap is minutes-per-day, not hours.

## 4. Feature fit

- **Widgets (2: "time since last feed" counter, active-timer).** Glance 1.2.0 is stable and pleasant: Compose-style `GlanceAppWidget` → RemoteViews. The honest part is the *refresh story*, not the API: `updatePeriodMillis` is clamped to ≥30 min; WorkManager periodic work floors at 15 min ([Glance update docs](https://developer.android.com/develop/ui/compose/glance/glance-app-widget), [known WorkManager+Glance update pitfall](https://issuetracker.google.com/issues/215813366)). Realistic design: render "**elapsed** = now − lastFeedStart" computed *inside* `provideGlance` at each update; refresh on (a) any local action or successful sync via `GlanceAppWidget.updateAll()`, (b) 15-min periodic WorkManager, (c) alarm-aligned catch-up. Worst-case staleness ~15 min on a static home screen — acceptable for this use case, and identical physics in Flutter (`home_widget`) or RN (native widget modules). Native advantage: widget taps deep-link straight into Activities with zero plugin layer.
- **Offline queue + conflict surfacing.** Room 2.8.5 as the local store and outbox (entity + `state: PENDING|SYNCING|FAILED|CONFLICT` + `serverUpdatedAt`), WorkManager 2.12.0 (`NetworkType.CONNECTED` constraint, exponential backoff) as the replay engine, server `modified` timestamps as the conflict detector. This is the single most idiomatic stack in Android development; every piece is documented, stable, and heavily represented in LLM training data.
- **Material 3 fidelity + dynamic color.** Compose M3 *is* the reference implementation — dynamic color from wallpaper, Expressive components stable in 1.4.0 ([M3 releases](https://developer.android.com/jetpack/androidx/releases/compose-material3)). Flutter's Material port approximates it; RN needs react-native-paper (M2-ish, M3 partial). For a client who specified Material 3, this is the strongest fit argument we have.
- **Background timer robustness.** No framework trickery needed: the timer's truth is "server start timestamp," UI displays `now − start`, so process death costs nothing. An optional foreground service (chronometer notification) covers the "see it running from the lock screen" case — first-class Android, plugin-land in the others.
- **Cleartext HTTP for self-hosted instances.** Trivial and precise: a `network_security_config.xml` allowing cleartext to user-configured LAN hosts (or debug-only `usesCleartextTraffic`). Same story in Flutter/RN honestly, but here it's one manifest file with zero plugin surface.

## 5. Performance & reliability

For a six-button app with a timeline, **performance barely differentiates the three candidates** — all are far below perceptibility thresholds at this scale. Where it does matter: **startup** — Compose with a Baseline Profile starts in a few hundred ms on the Pixel-class emulator here; we'd add `androidx.profileinstaller` (one dependency) if it ever regresses. **Memory** — a Compose app idles around 120–200 MB on modern Android; irrelevant for a foreground utility. **Timer accuracy** — solved by architecture (timestamp math), not framework; no ticking loop is the source of truth in any candidate. **Crash profile** — Kotlin's type system plus null-safety gives the lowest NPE-class crash rate of the three; the remaining risk is lifecycle bugs (ProcessDeath, `Service` kills), which JVM unit tests + the emulator QA loop (background mid-flow is in the QA persona's checklist) catch. I will not claim native is meaningfully "faster" here — that would be hand-waving; it's simply never the bottleneck for this workload.

## 6. Cross-platform upside (future iOS)

Real and better than Compose's reputation, but not free. **Path A (domain-only sharing):** move `domain/` + `data/` (Room KMP, DataStore, kotlinx-serialization, Ktor client replacing Retrofit — Retrofit/OkHttp are JVM-only) into a `shared` KMP module; write SwiftUI calling into it. Cost: **~2–4 weeks**, low risk, idiomatic-each-platform UI. **Path B (shared UI):** Compose Multiplatform for iOS has been stable since 1.8.0 (May 2025) ([JetBrains](https://blog.jetbrains.com/kotlin/2025/05/compose-multiplatform-1-8-0-released-compose-multiplatform-for-ios-is-stable-and-production-ready)); the UI layer ports largely as-is, but iOS-side widgets must still be written in WidgetKit/SwiftUI (Glance does not cross over), and you inherit Skia rendering (non-native feel, larger binary). Cost: **~4–8 weeks** including widgets and App Store polish. Design accommodation now, cheap later: keep `domain/` and `data/` free of `android.*` imports (pure Kotlin + kotlinx + androidx KMP-stable libs), inject the HTTP client behind an interface. That constraint costs us almost nothing today and preserves both paths.

## 7. GLM-5.3 codegen fit

Kotlin + Compose is among the best-covered mobile stacks in LLM training data: JVM lineage, Google's own docs/tutorials/codelabs, and a decade of Stack Overflow. Compose is common enough to have dedicated code-completion models trained on 50k+ Compose blocks ([AIT thesis, 2025](https://publications.ait.ac.at/en/publications/designing-and-developing-an-llm-model-for-code-completion-in-andr)); community benchmarks of LLMs on Android development exist and are improving ([Android Bench](https://www.reddit.com/r/LLMDevs/comments/1v32q0z/measuring_the_llms_for_android_app_development)). Known failure modes and how this project catches them:
- **Recomposition bugs / unstable lambdas** — the classic LLM Compose defect: unstable parameters (e.g., `List` in a data class without `@Immutable`), state read in the wrong scope, list items without `key`. Mostly *performance/behavior* smells, invisible to the compiler. Caught by: Compose compiler stability metrics config in CI, and the emulator QA loop (janky timeline scroll shows up in screenshots/logcat).
- **API version skew / hallucinated composables** — models reach for pre-1.0 or deprecated APIs ([vibe-coding postmortem](https://medium.com/@praveen.dheep/ai-vibe-coding-for-android-how-i-let-ai-write-my-kotlin-jetpack-compose-code-and-what-broke-738d6c072493)). Mitigation is structural: a version catalog (`libs.versions.toml`) pins reality, and a wrong import fails the ~6 s compile instantly — the fastest possible feedback loop for an agent.
- **Silent logic errors** (conflict resolution picking the wrong winner) — JVM unit tests; this is why commit ordering below puts pure-logic modules and their tests before UI.
On balance Kotlin's compile-time strictness (exhaustive `when`, nullability) converts many LLM mistakes into build errors rather than runtime bugs — a better failure mode for autonomous agents than JS/TS's looser floor.

## 8. Toolchain on this machine (verified today)

**Installed and working:** Android Studio **2025.3** with JBR **JDK 21.0.9**; Android SDK (`~/Library/Android/sdk`) with platforms 29–35, build-tools 35/36, platform-tools (adb), emulator + system images, two AVDs (Pixel5, Pixel_3a-API-32); a **running emulator (emulator-5554)**; Gradle wrapper 8.10.2 with warm dependency cache; the AGP **8.7.3** / Kotlin **2.0.21** / Compose BOM **2024.12.01** scaffold at `app/` assembles and installs (verified: build → install → launch → ui_describe). Xcode 26.3 + CocoaPods also present (relevant to the iOS future for *any* candidate). Node v25.8.1 present (helps RN, irrelevant to us). **Not installed:** Flutter SDK and Dart (`which flutter`/`which dart` — nothing; RN would need its own gem/cocoa setup beyond node). **One real gotcha found:** the shell default Java is Homebrew **JDK 25**, which **breaks Gradle 8.10.2/AGP 8.7.3 outright** (`What went wrong: 25.0.1`); agents must export `JAVA_HOME` to the Studio JBR or set `org.gradle.java.home` — worth baking into `AGENTS.md`/`gradle.properties` note so no agent loop dies on it. **Measured iteration numbers:** no-op incremental build **2.3 s**; single-file-change rebuild **~6.1 s**; streamed `adb install -r` **0.3 s**; launch **~0.1 s**; `ui_describe` ~1 s. (Clean-build time not measured to avoid wiping the scaffold's `build/` — first-thing-in-the-morning builds with a warm cache are realistically 30–60 s.) Kotlin 2.0.21 in the scaffold is 2 minor versions behind stable 2.4.21; upgrading is optional and low-risk but not required for any feature in scope.

## 9. Weaknesses vs Flutter/RN (what you'd have to accept)

1. **No browser QA/dev surface.** This is the big one and I concede it cleanly. Flutter web / RN web give agents a real DOM to drive headlessly (Playwright MCP, no emulator, parallelizable). Compose's web story is canvas-in-a-void (CMP/Wasm, Beta) or a *different* UI framework (Compose HTML). Everything UI must go through the emulator. Measured cost of that decision: ~10 s per warm verify cycle instead of ~1–2 s — real, but bounded; and it buys first-class coverage of the features (widgets, sync, background) a browser can't see anyway.
2. **Heavier toolchain and slower cold loops.** Android SDK + emulator vs `flutter run -d web-server` or Expo's dev server. First-build and first-emulator-boot friction is minutes, not seconds. Node/npm exists here; Flutter doesn't — ironically Flutter is the one needing a fresh install.
3. **Hot reload ergonomics favor the others.** Flutter's hot reload is best-in-class; RN Fast Refresh is excellent; Compose Live Edit is Android-Studio-centric and flaky outside it. Agents doing full rebuilds mostly don't care (6 s), but humans pair-programming will notice.
4. **AI training-data edge is Flutter's, marginally, for greenfield UI.** Dart ranks below Kotlin/Swift in codegen-quality tiering ([analysis](https://www.linkedin.com/posts/davidderemer_which-programming-language-dart-kotlin-activity-7427335643866763264-vu3x) — anecdotal), but Flutter's single-widget vocabulary means less API-surface guessing. On Android-native topics (widgets, WorkManager, Room) Kotlin wins; on generic UI churn it's a wash.
5. **Slower design-to-pixel iteration for non-M3 looks.** If the client later wants a bespoke non-Material brand, Flutter's everything-is-paintable model adapts faster than fighting/upholding M3 theming.
6. **iOS ceiling.** Kotlin iOS via KMP/CMP is viable (stable since May 2025) but younger than Flutter's iOS story, with smaller community and fewer ready answers when it breaks. If iOS next quarter is a hard requirement with one team, Flutter's claim is stronger than ours — say so plainly.

## 10. Implementation sketch

**Package layout** (single `:app` module, manual DI — no Hilt: fewer moving parts for AI agents to misfire on; ~10 files would justify Hilt, ~60 don't):

```
app/src/main/java/app/dandle/
├── BabyBuddyApp.kt              # Application; WorkManager init
├── MainActivity.kt              # single activity; testTagsAsResourceId (debug)
├── di/           AppContainer.kt                    # manual graph
├── core/         Clock.kt, AppResult.kt, Connectivity.kt
├── data/
│   ├── api/      BabyBuddyApi.kt, Dtos.kt, AuthInterceptor.kt   # Retrofit3+OkHttp5+kotlinx.serialization
│   ├── db/       AppDatabase.kt, EventDao.kt, Entities.kt       # Room 2.8
│   ├── prefs/    SessionStore.kt, SettingsStore.kt              # DataStore 1.2
│   └── repo/     EventRepository.kt, TimerRepository.kt, ChildRepository.kt
├── domain/       models.kt, Elapsed.kt, ConflictPolicy.kt       # pure Kotlin, KMP-ready
├── sync/         SyncWorker.kt, QueueDrain.kt                   # WorkManager 2.12
├── ui/
│   ├── theme/    Theme.kt (dynamic color), Type.kt
│   ├── nav/      NavGraph.kt
│   ├── login/    LoginScreen.kt, LoginViewModel.kt
│   ├── dashboard/ DashboardScreen.kt + VM          # six quick actions
│   ├── timers/   TimerListScreen.kt, TimerDetail.kt + VMs
│   ├── timeline/ TimelineScreen.kt + VM            # day-grouped LazyColumn
│   └── edit/     QuickEditSheet.kt + VM            # in-app edit of events
└── widget/       LastFeedWidget.kt, ActiveTimerWidget.kt, WidgetRefreshWorker.kt   # Glance 1.2
app/src/test/                                     # JVM: domain, repos (Robolectric+Room), VMs (turbine)
```

**Estimated size for the beta** (login + data layer + six quick actions + timers + timeline + offline queue with conflict surfacing + quick edit + 2 Glance widgets): **~7,000–9,000 LOC Kotlin** in main — roughly data 1,800 / sync 700 / domain 600 / UI 3,200 / widgets 700 / misc 500 — plus **~1,500 LOC of JVM tests**. Comparable to the Flutter/RN estimates; Kotlin is slightly denser than Dart but more ceremony than TS.

**First 3 commits:**
1. **"Toolchain + theme skeleton":** pin current stable deps in `libs.versions.toml` (Compose BOM 1.12.x era, Room, DataStore, WorkManager, Glance, Retrofit 3, OkHttp 5, kotlinx-serialization), JBR/`org.gradle.java.home` note, `network_security_config.xml`, M3 theme with dynamic color, root `semantics { testTagsAsResourceId = true }` in debug, empty dashboard route. Exit criterion: `assembleDebug` + install + `ui_describe` shows tagged root node.
2. **"Login + session":** DataStore session store, Retrofit/OkHttp client with auth interceptor against Baby Buddy's REST (`/api/` token auth per `docs/research/babybuddy-api.md`), LoginScreen with `login/server_url|username|password|submit` tags, JVM tests for the auth repository (credential errors → user-visible states). QA-agent-verifiable end to end against the Docker server.
3. **"Local-first event write + outbox":** Room schema (events + sync queue), dashboard's six quick-action buttons writing locally and enqueuing `SyncWorker` with backoff, `PENDING/FAILED/CONFLICT` states surfaced on the (empty) timeline, JVM tests for `ConflictPolicy` covering the stale-vs-fresh matrix. After this commit the QA persona can already run its offline airplane-mode scenario.

---

### Source index
- Compose & M3 releases: [compose](https://developer.android.com/jetpack/androidx/releases/compose) · [compose-material3](https://developer.android.com/jetpack/androidx/releases/compose-material3) · [Dec '25 Compose release blog](https://android-developers.googleblog.com/2025/12/whats-new-in-jetpack-compose-december.html)
- Kotlin: [releases](https://kotlinlang.org/docs/releases.html) · [2.2.0 announcement](https://blog.jetbrains.com/kotlin/2025/06/kotlin-2-2-0-released)
- Room/DataStore/WorkManager/Glance: [room](https://developer.android.com/jetpack/androidx/releases/room) · [datastore](https://developer.android.com/jetpack/androidx/releases/datastore) · [work](https://developer.android.com/jetpack/androidx/releases/work) · [glance](https://developer.android.com/jetpack/androidx/releases/glance) · [Room KMP setup](https://developer.android.com/kotlin/multiplatform/room)
- Widgets: [Glance update semantics](https://developer.android.com/develop/ui/compose/glance/glance-app-widget) · [update-frequency pitfall](https://issuetracker.google.com/issues/215813366)
- Semantics/UiAutomator: [Compose semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics)
- KMP/CMP: [CMP 1.8 iOS stable](https://blog.jetbrains.com/kotlin/2025/05/compose-multiplatform-1-8-0-released-compose-multiplatform-for-ios-is-stable-and-production-ready) · [CMP 1.9 / web Beta](https://blog.jetbrains.com/kotlin/2025/09/compose-multiplatform-1-9-0-compose-for-web-beta) · [compose-multiplatform repo](https://github.com/jetbrains/compose-multiplatform) · [KMP FAQ](https://kotlinlang.org/docs/multiplatform/faq.html)
- Network stack: [OkHttp CHANGELOG](https://github.com/square/okhttp/blob/master/CHANGELOG.md) · [Retrofit 3 guide](https://proandroiddev.com/retrofit-3-0-0-detailed-migration-guide-0d2c043d43e3) · [kotlinx.serialization releases](https://github.com/Kotlin/kotlinx.serialization/releases)
- AI codegen: [AIT Compose-LLM thesis](https://publications.ait.ac.at/en/publications/designing-and-developing-an-llm-model-for-code-completion-in-andr) · [Android Bench](https://www.reddit.com/r/LLMDevs/comments/1v32q0z/measuring_the_llms_for_android_app_development) · [AI-Android-code review](https://dev.to/myougatheaxo/i-asked-a-senior-engineer-to-review-ai-generated-android-code-heres-what-he-said-509) · [vibe-coding pitfalls](https://medium.com/@praveen.dheep/ai-vibe-coding-for-android-how-i-let-ai-write-my-kotlin-jetpack-compose-code-and-what-broke-738d6c072493)
- Competitors (for §9): [Flutter archive/releases](https://docs.flutter.dev/install/archive) · [RN 0.82](https://reactnative.dev/blog/2025/10/08/react-native-0.82) · [Expo New Architecture docs](https://docs.expo.dev/guides/new-architecture) · [State of RN 2026](https://www.ditto.com/blog/the-state-of-react-native-in-2026)
- Local verification (this machine, 2026-10-16): build/install/launch timings, `ui_describe` tree capture, JDK-25-vs-Gradle failure — commands and outputs in the evaluation session; reproducible per §3 and §8.
