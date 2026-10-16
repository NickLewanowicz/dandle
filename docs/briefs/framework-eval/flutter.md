> **STATUS: Draft — Flutter candidacy for framework evaluation.** One of three candidates (Native Kotlin/Compose, React Native/Expo, Flutter). Written by the Flutter-engineer persona, 2026-10-16. Versions verified against pub.dev / flutter.dev on this date; local machine facts verified read-only on this Mac.

# Framework brief — Flutter (candidate)

## 1. Executive summary

- **Verdict: Flutter is a strong #2 for this project.** Viable, well-tooled, and its web build gives our AI QA agent a genuinely faster loop than APK+emulator for ~70% of the app. But it loses to native Kotlin where this project is most exposed: the two home-screen widgets still require hand-written Kotlin (home_widget only shuttles data), Flutter has officially declined to implement Material 3 Expressive (Compose ships it), and Dart's LLM codegen quality ranks measurably below Kotlin/TypeScript.
- The framework is healthy in late 2026: Flutter 3.47 / Dart 3.13 stable, Impeller mandatory on Android 10+, hot reload on web default since 3.35.
- For an Android-only, six-buttons-plus-timeline app with an offline queue and 2 native widgets, Flutter's cross-platform payoff is mostly theoretical; the QA-loop payoff is real; the "pure Flutter" story is an illusion for our exact feature set.
- If the client weights AI-QA-through-browser and future iOS above widget polish and native fidelity, Flutter is defensible. If widgets and Material-You fidelity lead, Kotlin wins.

## 2. 2026 state of the art

Everything we would use, verified October 2026:

| Component | Version / state (Oct 2026) | Notes for this project |
|---|---|---|
| Flutter stable | **3.47** (2026-08-12); ~3 releases/yr (3.41 Feb, 3.44 May, 3.47 Aug) | Widget Preview (per-component rendering without a full app run) is stable in 3.47 — nice for our ux-designer persona. [What's new in 3.47](https://flutter.dev/blog/whats-new-in-flutter-3-47) |
| Dart | **3.13** (ships with 3.47), sound null safety long settled | Records/patterns mature; no migration debt for a new app. [Dart what's new](https://dart.dev/resources/whats-new) |
| Impeller (Android) | Default since 3.29; **Skia removed for Android 10+** in mid-2026, opt-out being deleted | Our minSdk is 29 (Android 10) — Impeller is our only renderer. Fine on modern devices; residual complaints exist for very low-end devices, which our audience (parents' hand-me-down phones) may include. [Impeller docs](https://docs.flutter.dev/perf/impeller) |
| Material 3 in Flutter | Baseline M3 default since 3.16, solid for buttons/sheets/lists/NavBars | **M3 Expressive will NOT be implemented** — the Flutter team said so in [flutter/flutter#168813](https://github.com/flutter/flutter/issues/168813). Compose ships Expressive. Known fidelity gaps: `ColorScheme.fromSeed` needed a fidelity variant to match native output ([breaking-change doc](https://docs.flutter.dev/release/breaking-changes/new-color-scheme-roles)); dynamic color ignores the user's system contrast setting ([material-foundation/flutter-packages#627](https://github.com/material-foundation/flutter-packages/issues/627)). |
| dynamic_color | **2.1.0** (~2 months old; 638 likes) | Wallpaper-derived schemes on Android 12+, `DynamicColorBuilder`, harmonization, test-mocking package. This is the standard path to Material You. [pub.dev/packages/dynamic_color](https://pub.dev/packages/dynamic_color) |
| home_widget | **0.10.0** (29 days old; ~2.2k likes) | Handles data push (Dart→widget), widget refresh, and interactive-widget callbacks into Dart. **Does NOT let you write the widget UI in Flutter** — layout is native Kotlin (RemoteViews XML or Glance). [pub.dev/packages/home_widget](https://pub.dev/packages/home_widget), [Google codelab](https://codelabs.developers.google.com/flutter-home-screen-widgets) |
| drift | **2.35.2** (9 days old; 2.5k likes) | Type-safe SQLite, reactive streams, migrations, `NativeDatabase.memory()` for instant unit tests, **works on web (sqlite3/WASM)** — so the offline DB runs in browser QA too. [pub.dev/packages/drift](https://pub.dev/packages/drift) |
| workmanager | **0.10.10** (39 days old; 2.4k likes) | Wraps Jetpack WorkManager; periodic work floor is 15 min (Android-enforced). Web/macOS support is experimental (Service Worker). Historically slow-maintained under fluttercommunity — a watch-item, though for our single `SyncWorker` the API surface is tiny. [pub.dev/packages/workmanager](https://pub.dev/packages/workmanager) |
| State management | flutter_riverpod **3.4.3** (42 days old) | For an app this size, even `ValueNotifier`+`InheritedWidget` would do; Riverpod 3 is the mainstream default. [pub.dev/packages/flutter_riverpod](https://pub.dev/packages/flutter_riverpod) |
| HTTP | `dio` 5.x or `package:http` | Both mature; cleartext HTTP is a pure AndroidManifest concern (same as native). |
| Hot reload | Android/iOS: excellent (<1 s), as always. **Web: default since 3.35** (3.32 introduced it experimentally) | [Flutter 3.32 blog](https://flutter.dev/blog/whats-new-in-flutter-3-32) |

## 3. AI-agent QA ergonomics (the #1 criterion)

Honest answer first: **the browser loop is real, faster, and more deterministic for UI/logic — but it cannot see roughly a third of our feature surface, so the emulator loop never goes away.** Details:

### 3.1 The browser loop (Flutter web + Playwright-class automation)

Mechanism: Flutter web renders to `<canvas>` (CanvasKit or skwasm/WasmGC — [renderer guide](https://sagnikbhattacharya.com/blog/flutter-web-skwasm-vs-canvaskit)). There is **no DOM of buttons**. Automation works only through Flutter's semantics layer: when enabled, Flutter mirrors its Semantics tree into `<flt-semantics>` DOM elements with ARIA roles, which Playwright sees in its accessibility snapshot ([Flutter web a11y blog](https://flutter.dev/blog/accessibility-in-flutter-on-the-web), [web a11y docs](https://docs.flutter.dev/ui/accessibility/web-accessibility)).

**Semantics is NOT on by default on web.** The app must call `SemanticsBinding.instance.ensureSemantics()` (a 3-line debug-mode hook in `main()`), or the user presses an invisible `aria-label="Enable accessibility"` button ([docs](https://docs.flutter.dev/ui/accessibility/web-accessibility)). For our QA setup this is a one-time, deliberate change we ship in debug builds. Since 3.32 the semantics path also got ~30% faster frame times and finer-grained ARIA roles.

Concrete loop our QA agent would run:

| Step | Time (measured-class estimates, M1 Pro host) |
|---|---|
| One-time: add `CORS_ALLOWED_ORIGINS=http://localhost:<port>` to the babybuddy Docker env — the server **already ships django-cors-headers**, gated on this env var (verified in `.scratch/babybuddy/babybuddy/settings/base.py` lines 337–341). Without it, browser fetches to `localhost:8000` are CORS-blocked. | 2 min, once |
| Session start: `flutter run -d chrome` (keeps hot reload; debug build compiles in 30–90 s) or serve `flutter build web` output statically | 30–90 s |
| Per code change: hot reload | < 1 s |
| Per assertion: Playwright `browser_snapshot` (ARIA tree) + click/type by role+name + screenshot | 0.1–0.5 s per action |
| Restart app state | browser reload, 2–5 s |

Determinism: **high.** No emulator boot (30–60 s saved per session), no GPU/Doze flakiness, stable role/name selectors, parallelizable tabs, screenshots free. Node 25 + Playwright-class tooling already exist on this machine.

Caveats the client must internalize:

1. **Selectors are role+label, never ids.** Flutter web semantics nodes don't carry resource-ids; QA selectors are only as good as our semantic labels. We must treat semantic labels as API (same discipline as `contentDescription` on Android).
2. **Debug web (DDC) vs release web (AOT/Wasm) are different compilers.** QA should occasionally run against `flutter build web` output; behavior differences are rare but real.
3. **Visual fidelity is close but not pixel-identical** to Impeller-on-Android (font shaping, shadows, hover/scrollbar adaptations). Layout/overflow bugs — the classic Flutter failure — do reproduce on web.
4. Never testable in a browser, ever: **both home-screen widgets** (no home screen), **background sync via WorkManager** (workmanager's web mode is an experimental Service Worker, not our Android path), **cleartext-HTTP behavior** (a manifest concern — on web it's CORS instead), **dynamic color from wallpaper**, **timer behavior across backgrounding** (browsers throttle timers differently than Android), airplane-mode offline paths (mockable in browser via API stubs, but the real radio-off path is emulator-only).

### 3.2 The emulator loop (APK + UiAutomator MCP tools)

Mechanism: our QA uses UiAutomator-backed MCP tools (`android_ui_describe`, `android_ui_resolve`). Flutter apps are one opaque canvas view to UiAutomator **unless the Semantics tree is enabled** — same `ensureSemantics()` hook, shipped in debug builds, enabled at startup so there's no race. When enabled, Flutter's Android accessibility bridge exposes virtual views with text/labels/contentDescriptions.

The painful part, specific to Flutter on Android: **virtual views have no `resource-id`** — Flutter has no equivalent of `accessibilityIdentifier`/`android:id` ([flutter/flutter#137735](https://github.com/flutter/flutter/issues/137735), open as of 2026). UiAutomator best practice (stable `resource-id` selectors) is impossible; we fall back to text/contentDescription matching, which collides when two entries share a label ("Feeding" appears N times in a timeline — disambiguation becomes index-based and flaky). For contrast, Compose exposes `testTag` as `resource-id` via `testTagsAsResourceId` ([Compose testing cheatsheet](https://developer.android.com/develop/ui/compose/testing-cheatsheet)) — the native candidate gets id-based selectors; we don't.

| Step | Time |
|---|---|
| Emulator boot (if cold) | 30–60 s (Pixel5 AVD already exists) |
| Build + install (`flutter build apk --debug` incremental, or `flutter run`) | 20–90 s |
| Launch | ~5 s |
| Per step: `ui_describe`/`ui_resolve`/tap/type | 0.5–2 s |
| Screenshot verify | 1–2 s |
| Known flakiness | semantics-tree build latency after launch; index-based disambiguation of repeated labels; WebView-free so no hybrid quirks |

### 3.3 Verdict on the QA criterion

Browser-Flutter-web is a ~3–5x faster loop for login, quick actions, timeline, quick-edit, and (via drift-web) even outbox/conflict UI logic — probably 60–70% of QA volume. It is strictly better for QA than the native-Kotlin candidate (which has **no** browser representation at all — Compose Multiplatform web is not a QA-grade option) and worse than RN/Expo (react-native-web renders real DOM; no semantics toggle needed). But an emulator lane remains mandatory for widgets, WorkManager sync, backgrounding, and dynamic color. Budget: two lanes, one app — `flutter run -d chrome` for the fast lane, `flutter run` on Pixel5 for the truth lane.

## 4. Feature fit

- **Android home-screen widgets (Feature: 2 widgets).** With home_widget 0.10, Dart pushes data (`HomeWidget.saveData` + `updateWidget`) and interactive-widget button presses route back into Dart via callback keys. **The widget layout itself is native Kotlin no matter what** — RemoteViews XML or Glance composables, plus `AppWidgetProvider`, receiver/manifest wiring. Realistic cost: ~500–800 LOC of Kotlin + XML for a timer widget (running timer + start/stop) and a recent-entries widget (RemoteViews `ListView`/Glance `LazyColumn`). So "pure Flutter" is an illusion for our feature set; the Kotlin candidate writes the same widget code with Glance in its native idiom and shares theme tokens directly.
- **Offline queue.** drift 2.35 is the right tool and is genuinely excellent: Room-equivalent schema+migrations, reactive streams for the UI, `syncState`/`failureReason` columns map 1:1 to Brief 00's outbox pattern, and in-memory DBs make the test-engineer's unit tests fast and emulator-free. Bonus: drift runs on web, so the browser QA lane exercises the same outbox logic.
- **Material 3 fidelity.** Baseline M3 (six quick-action buttons, bottom sheets, timeline cards, top app bar) is fully covered and looks native-adjacent. Dynamic color via dynamic_color 2.1 works. Honest gaps vs native Compose: no M3 Expressive (team declined, [#168813](https://github.com/flutter/flutter/issues/168813)); system contrast setting ignored ([#627](https://github.com/material-foundation/flutter-packages/issues/627)); subtle scheme differences from native Material Color Utilities output. None of these break our flows; all of them are visible to a sharp eye next to a native app.
- **Timers.** Dart timers on the UI isolate pause when Android backgrounds the app ([background processes docs](https://docs.flutter.dev/packages-and-plugins/background-processes), [flutter#94094](https://github.com/flutter/flutter/issues/94094)) — so we implement timers exactly like Brief 00 pattern #2: persist `startedAt`, compute elapsed from an injectable clock, use a `Ticker` only for the visible display (tickers mute when not visible). Same design as native; no Flutter penalty.
- **Cleartext HTTP.** Identical to native: `android:usesCleartextTraffic` / network security config in `android/app/src/main/AndroidManifest.xml`. No Flutter-specific friction. On web, the equivalent concern is CORS (handled per §3.1).

## 5. Performance & reliability

For a self-hosted baby tracker on Android 10+ hardware, this section barely matters, so let me say that plainly and keep it short.

- **Startup:** Impeller+engine init adds roughly 100–300 ms cold start over a native Compose app on mid-range hardware. For our app: sub-2 s either way. Nobody opens a feeding timer and cares about 200 ms.
- **Memory:** Flutter baseline (~90–150 MB) vs Compose (~60–100 MB). Both fine on 2 GB+ devices; our data model (a few hundred rows) is noise.
- **APK size:** arm64 release APK typically ~18–25 MB vs ~10–14 MB native (engine adds ~5–6 MB; [FAQ](https://docs.flutter.dev/resources/faq)). Irrelevant for a sideloaded/self-hosted audience.
- **Timer accuracy:** correct by construction with timestamp math (§4). Impeller removes the old shader-jank class of issues; residual jank reports are confined to very low-end devices ([Impeller docs](https://docs.flutter.dev/perf/impeller)).

## 6. Cross-platform upside

- **iOS from this Mac:** real. Xcode 26.3 is installed; `flutter build ios --simulator` + the ios-simulator QA tooling works today for the sim loop. One wrinkle: system CocoaPods is ancient (1.11.3) — Flutter 3.47's preferred path is Swift Package Manager (`flutter config --enable-swift-package-manager`), so we'd sidestep CocoaPods entirely. Device/TestFlight builds need signing certs the client would provide.
- **The "one codebase" story, honestly:** app UI, drift DB, outbox, API client, quick-edit, conflicts — one Dart codebase, truly shared. But the widgets remain **two Kotlin islands** (~500–800 LOC) regardless, so total Kotlin written is only ~30–40% less than the widget work in the native plan, while Kotlin knowledge is still required on the team. Platform channels beyond widgets: zero needed (http/drift/workmanager/home_widget all wrap their own native glue inside the plugins).
- Net: the iOS upside is genuine but hypothetical for a client whose product is an Android app today. Cross-platform is a later unlock, not a beta requirement.

## 7. GLM-5.3 codegen fit

- **Training-data reality:** Dart ranks well below Python/TypeScript/Kotlin in LLM training corpora — informally "Tier 4" for codegen quality ([DeRemer analysis](https://www.linkedin.com/posts/davidderemer_which-programming-language-dart-kotlin-activity-7427335643866763264-vu3x)); research confirms LLM preference/excellence concentrates in Python and JS/TS ([arXiv 2503.17181](https://arxiv.org/html/2503.17181v2)). Expect more hallucinated plugin APIs and stale patterns (Provider-era code, pre-M3 widgets) than with the other two candidates.
- **Known failure modes in Flutter codegen:** widget-tree nesting/`RenderFlex overflow` layout errors; forgotten `const` (lint-only); `BuildContext` used across `async` gaps; `StatelessWidget`/`StatefulWidget` and provider-scope misuse; invented package APIs.
- **Why this is survivable here:** Flutter's static analysis is strict and fast (`flutter analyze` as a zero-warning CI gate catches most API hallucinations), and **widget tests run without an emulator in ~seconds**, asserting structure/layout deterministically — a cheap deterministic net that catches nesting/layout slop before QA ever sees it. Dart's strictness partially compensates for corpus size ([contrarian data point](https://dev.to/gde/why-i-tell-my-ai-coding-agent-prefer-dart-over-python-1dbg)). Plan: analyzer gate + widget tests per feature + semantic labels written deliberately (they double as QA selectors — see §3).

## 8. Toolchain on this machine (verified read-only, 2026-10-16)

- **Flutter/Dart: not usable today.** `which flutter dart` finds nothing. `~/flutter` exists but is **Flutter 2.10.3 from 2022-03-02** (2.9 GB, x86_64 macOS build, pre-Impeller, pre-M3-default) — it must be replaced, not upgraded: delete/replace with a fresh stable clone (~1.1–1.3 GB download, ~3.5–4.5 GB extracted) and add to PATH.
- **Java:** `java` on PATH is Homebrew **OpenJDK 25.0.1** — too new for Gradle 8.x (the project scaffold's wrapper is 8.10.2, and Flutter's AGP template rides the same 8.x line). However **`openjdk@17` is installed** via Homebrew, so the fix is `JAVA_HOME`/`org.gradle.java.home` pointing at 17 (AGENTS.md already prescribes JDK 17). No install needed; configuration only.
- **Android:** SDK complete for us — platforms 29–35 (incl. 35), build-tools 35/36, emulator, and the `Pixel5` AVD our QA already uses. `flutter doctor` + license acceptance: minutes.
- **Node 25.8.1 + Chrome installed:** the web QA lane needs nothing new. Xcode 26.3 present for iOS sims.
- **Install/first-build estimate (M1 Pro, 16 GB, 83 GB free):** SDK download 3–15 min (network-dependent); total new disk ≈ **6–8 GB** (SDK ~4 GB, pub cache ~0.5 GB, Gradle/AGP/Kotlin caches ~1.5–2 GB); first `flutter run` on the emulator 5–10 min cold; steady-state `flutter run` 30–60 s, hot reload <1 s, `flutter run -d chrome` 30–90 s. **Setup cost: roughly 30–45 minutes, one time.**
- **vs the existing Gradle scaffold:** the working scaffold (`app/`, AGP 8.7.3, Kotlin 2.0.21, Compose BOM, min 29/target 35) is Kotlin-specific and would be **discarded** — `flutter create` generates its own `android/` host. That's the honest toolchain loss versus the native candidate: the Flutter path starts by throwing away working scaffolding, while its iteration loop afterward is at least as fast (hot reload parity with Compose, plus the web lane the native path lacks).

## 9. Weaknesses vs Kotlin and RN (what the client must accept)

- **vs Native Kotlin/Compose:**
  1. Widgets need native Kotlin anyway → "pure Flutter" is an illusion for our exact feature set; we pay two toolchains and keep Kotlin on the team, for a feature the native plan does natively.
  2. No M3 Expressive, ever (officially declined, [#168813](https://github.com/flutter/flutter/issues/168813)); Compose has it. Dynamic color misses system contrast ([#627](https://github.com/material-foundation/flutter-packages/issues/627)).
  3. UiAutomator QA is structurally worse: no `resource-id` on Flutter views ([#137735](https://github.com/flutter/flutter/issues/137735)) vs Compose's `testTagsAsResourceId` — our emulator QA is text-matching and flakier than the native path's.
  4. Throws away the working Gradle scaffold; adds ~6–8 GB and 30–45 min setup.
  5. Engine overhead (startup/memory/APK size) — small, but strictly nonzero where native is zero.
- **vs React Native/Expo:**
  1. RN's browser-QA story beats ours: react-native-web renders real DOM, so Playwright sees actual elements with no semantics toggle and no canvas layer.
  2. TypeScript >> Dart for AI codegen quality — the same #1-criterion logic that favors our web lane also favors RN's training data.
  3. RN also still needs native widget code, so our widget disadvantage vs native is shared, but our Dart-corpus disadvantage vs RN is not.
- **Where Flutter genuinely wins this comparison:** the drift-based offline stack is more ergonomic than anything in RN (drift-web runs in the browser QA lane); hot reload remains best-in-class; and the web lane exists at all, which is more than the native candidate can say.

## 10. Implementation sketch

**Layout** (single Flutter app package, mirroring Brief 00's shape so the spec maps 1:1):

```
lib/
  main.dart               # bootstrap, ensureSemantics() in debug, WorkManager init
  app.dart                # MaterialApp, M3 ThemeData, DynamicColorBuilder
  core/                   # api client (dio), auth flow (csrf→session→api_key),
                          # settings (serverUrl/apiKey/childId), injectable Clock
  data/                   # drift DB: events, timers, outbox tables; repositories (streams)
  sync/                   # outbox drainer + workmanager registration + conflict mapping
  features/
    login/  dashboard/  quick_actions/  timeline/  quick_edit/  conflicts/
android/app/src/main/kotlin/net/babybuddy/next/widget/   # KOTLIN, unavoidable:
    TimerWidget.kt  TimelineWidget.kt  (+ AppWidgetProviders, RemoteViews XML)
test/                     # unit (drift in-memory, clock) + widget tests (no emulator)
```

**Estimated LOC for the beta** (parity + quick-edit + offline queue/conflicts + 2 widgets):

| Area | LOC |
|---|---|
| Dart: core + data + sync (api, auth, drift schema/repos, outbox worker) | ~2,000–2,500 |
| Dart: UI (login, dashboard, 6 quick actions, timeline, quick-edit sheets, conflict resolution) | ~2,000–2,800 |
| Dart: glue, theme, semantic labels, DI | ~500 |
| Kotlin + XML: two widgets via home_widget (unavoidable native) | ~500–800 |
| Tests (unit + widget) | ~2,000–3,000 |
| **Total** | **~7,000–9,500** |

Comparable to the native estimate within ±15% — Flutter's verbosity in UI roughly cancels its shorter data layer; the widgets are identical native cost in both plans.

**First 3 commits:**

1. **Scaffold + gates.** Replace stale `~/flutter` with 3.47 stable; `flutter create` into `app/` (Flutter host, `net.babybuddy.next`, minSdk 29/target 35); debug-mode `ensureSemantics()` hook; `flutter analyze` + empty `flutter test` green; README pinning `JAVA_HOME` to `openjdk@17`.
2. **Auth + API core.** dio client, login flow against `localhost:8000` (csrf→session→api_key per `docs/research/babybuddy-api.md`), secure token storage, settings screen skeleton; mocked-HTTP unit tests for the offline path.
3. **Drift + first vertical slice.** drift schema (events/timers/outbox, `syncState`/`failureReason`), repositories as streams, dashboard with child switcher and the Diaper quick action end-to-end (local write → outbox row), in-memory drift tests.

---

### Source index

- Flutter 3.47 / Dart 3.13: https://flutter.dev/blog/whats-new-in-flutter-3-47 · release notes: https://docs.flutter.dev/release/release-notes
- Impeller mandatory on Android 10+: https://docs.flutter.dev/perf/impeller
- M3 Expressive declined: https://github.com/flutter/flutter/issues/168813 · color-role fidelity: https://docs.flutter.dev/release/breaking-changes/new-color-scheme-roles
- dynamic_color: https://pub.dev/packages/dynamic_color · contrast gap: https://github.com/material-foundation/flutter-packages/issues/627
- home_widget: https://pub.dev/packages/home_widget · codelab: https://codelabs.developers.google.com/flutter-home-screen-widgets
- drift: https://pub.dev/packages/drift · workmanager: https://pub.dev/packages/workmanager · WorkManager 15-min floor: https://developer.android.com/develop/background-work/background-tasks/persistent
- Web a11y/semantics: https://docs.flutter.dev/ui/accessibility/web-accessibility · https://flutter.dev/blog/accessibility-in-flutter-on-the-web · Flutter 3.32 semantics perf: https://flutter.dev/blog/whats-new-in-flutter-3-32 · renderers: https://sagnikbhattacharya.com/blog/flutter-web-skwasm-vs-canvaskit
- Playwright-vs-canvas testing: https://getautonoma.com/blog/flutter-playwright-testing-guide · https://www.devassure.io/blog/flutter-web-app-agent-testing
- UiAutomator/resource-id gap: https://github.com/flutter/flutter/issues/137735 · Compose counter-example: https://developer.android.com/develop/ui/compose/testing-cheatsheet · https://docs.flutter.dev/ui/accessibility/accessibility-testing
- Background timers: https://docs.flutter.dev/packages-and-plugins/background-processes · https://github.com/flutter/flutter/issues/94094
- Dart codegen quality: https://arxiv.org/html/2503.17181v2 · https://www.linkedin.com/posts/davidderemer_which-programming-language-dart-kotlin-activity-7427335643866763264-vu3x · https://dev.to/gde/why-i-tell-my-ai-coding-agent-prefer-dart-over-python-1dbg
- Hot reload on web: https://flutter.dev/blog/whats-new-in-flutter-3-32 · https://docs.flutter.dev/tools/hot-reload
- APK size: https://docs.flutter.dev/resources/faq
