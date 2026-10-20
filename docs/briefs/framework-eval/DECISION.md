---
title: "Framework decision"
---

# FRAMEWORK DECISION — Native Kotlin + Jetpack Compose

**Decided by:** Maintainer · **Date:** 2026-10-16 · **Status:** FINAL for the beta; revisit triggers in §4.

Candidate briefs: [kotlin-compose.md](kotlin-compose.md) · [flutter.md](flutter.md) · [react-native.md](react-native.md). All three were researched and locally verified by expert personas on 2026-10-16.

## 1. Scoring vs the owner's priorities (1 = best)

| Criterion (owner's order) | Kotlin/Compose | Flutter | RN/Expo |
|---|---|---|---|
| **AI-agent QA ability** | **1** — measured \<10 s warm build→install→drive loop on this machine; `testTagsAsResourceId` gives the most stable agent selectors of the three; ~80% of tests JVM-only | 3 — web QA only via opt-in semantics bridge; no `resource-id` on device (upstream issue) = flaky emulator automation | 2 — real-DOM browser loop is genuinely the fastest for ~90% of UI flows, but widgets/background/install (our headline features) can NEVER be browser-QA'd by any framework; two loops = twice the maintenance |
| **Performance & reliability** | **1** — ~0.5–1 s startup, ~5–8 MB APK, no JS/Dart runtime, no yearly SDK treadmill | 2 | 3 — 1–2 s start, 15–25 MB APK, ~3 Expo SDKs/year churn |
| **Feature fit (widgets! M3! offline!)** | **1** — Glance 1.2 stable, M3 reference implementation incl. Expressive + one-line dynamic color, WorkManager native | 2 — both widgets need ~500–800 LOC native Kotlin anyway; declined M3 Expressive upstream | 3 — single-maintainer widget lib = our biggest supply-chain risk on a headline feature |
| **Cross-platform upside (iOS later)** | 2 — domain-only KMP ~2–4 wks; full CMP ~4–8 wks; iOS widgets SwiftUI regardless | 2 | **1** — ~95% shared iOS; but iOS widgets are a separate effort for everyone |
| **GLM-5.3 codegen fit** | **1** — huge corpus + compile-time strictness converts LLM mistakes into 6-second build errors (best autonomous failure mode) | 3 — Dart tier-4 per its own expert | 2 — largest corpus but version-drift failure mode |
| **Overhead on this machine** | **1** — toolchain validated today, building today | 3 — SDK not installed (~1.1–1.3 GB, 30–45 min) | 2 — node present; ~1 GB node_modules + 5–15 min first build |

**Result: Kotlin/Compose.**

## 2. Rationale

1. **The owner's #1 criterion survives contact with the evidence — with a twist.** The hypothesis "browser QA is easier for agents" is *true only for RN*. But all three experts agree on the decisive fact: browser QA cannot exercise home-screen widgets, background sync, cleartext HTTP, or install/first-run — and widgets are Feature 2 of this project. The emulator lane is mandatory in every world. Kotlin's emulator lane was **measured, on this machine, by our own agent, today**: sub-10-second warm verify cycles with a deterministic accessibility tree and the strongest selector stability of the three candidates. That is fast enough that the browser-loop advantage buys minutes per day, not hours — while costing a second QA stack, a CORS dev-proxy, and WASM-sqlite-vs-device-sqlite divergence in exactly the logic we care most about (the sync engine).
2. **Our differentiating features are native features.** Widgets (Glance, first-class), Material 3 dynamic color (Compose *is* the reference implementation), background replay (WorkManager without a wrapper), timers surviving process death. RN routes all four through third-party shims of varying maturity; Flutter routes two of them through native Kotlin anyway — "pure Flutter" doesn't exist for this scope.
3. **Autonomous-agent failure modes favor the compiler.** Kotlin turns a whole class of LLM mistakes into instant build errors; JS/TS turns them into runtime bugs an agent must catch through QA loops. For a team of agents working unattended, build-errors-beat-runtime-surprises.
4. **The owner's stated lean was native** ("more performant and simple overall with less overhead") — the evidence did not contradict it; it strengthened it.
5. **Rewrite risk is already mitigated.** The canonical spec ([docs/flows/new-app/00-functional-spec.md](../../flows/new-app/00-functional-spec.md)) is framework-agnostic by design; the UX spec and API research complete the contract. If this call proves wrong, re-platforming is a spec-reading exercise, not an archaeology project.

## 3. What we accept (consequences)

- **No browser QA surface.** QA agents work through the emulator + JVM tests. Mitigations: enforce `testTagsAsResourceId` + a `resourceId` naming convention from commit 1; animation scales zeroed in the test harness; 6-second incremental builds.
- **Human hot-reload ergonomics are worse than Flutter's.** Acceptable: this project is built by agents doing full rebuilds.
- **iOS later means either SwiftUI-against-KMP-domain (~2–4 wks) or full CMP (~4–8 wks).** Cost control starts now: `domain/` and `data/` stay free of `android.*` imports; HTTP client behind an interface.
- **JDK gotcha:** system Java is Homebrew JDK 25 which breaks Gradle/AGP; all builds MUST use the Android Studio JBR 21 (`scripts/dev-env.sh`).

## 4. Revisit triggers

Re-open this decision (RN/Expo is the designated fallback) if any of these prove true in the beta:
1. Warm emulator verify cycles consistently exceed ~30 s or flake >5% of QA runs after harness tuning.
2. Agent QA throughput becomes the bottleneck for shipping (not merely slower than a hypothetical browser loop).
3. A hard requirement emerges for iOS within one quarter AND domain-only KMP proves insufficient.

## 5. Implementation sequencing (immediate)

Per [Brief 00](../00-architecture.md) + [Brief 01](../01-login-and-data-layer.md) + the Kotlin brief's commit plan:
1. Deps/theme/toolchain skeleton + testTag infrastructure (exit: tagged node visible to `ui_describe`).
2. Login + session + API layer (exit: QA-able login against Docker server).
3. Room schema + outbox + repositories (exit: offline airplane-mode scenario drivable).
4. Six quick actions + timeline UI (exit: full parity core, online).
5. Quick edit sheet + offline states polish; widgets; M3 polish; test/audit passes.
