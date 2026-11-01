---
title: "Brief 04: Login rebrand & data-mode picker"
---

> **STATUS: Draft — awaiting owner approval.** UX spec reviewed by ux-designer persona (2026-10-31); implementation pairing with Brief 05 (the picker's device/cloud modes are enabled by Brief 05 phases 4–5).

# Brief 04 — Login Rebrand & Data-Mode Picker

**Owner:** Maintainer → android-engineer · **Depends on:** Brief 05 phasing (picker cards), `docs/design/icons.md` (asset pipeline conventions), `docs/flows/new-app/10-login.md` (current flow), AGENTS.md #3 (pinned Baby Buddy login behavior).

## Goal

1. Replace every piece of "Baby Buddy" *app* branding with **Dandle** branding: a logo on the login screen (AI-generated via the owner's Replicate token), a new tagline, and cleanup of stray "Baby Buddy" strings that refer to the app (not the server).
2. Restructure the login screen around a first decision — **"Where should your data live?"** — with three modes: *This device only*, *Baby Buddy server*, *Dandle Cloud*. The existing two-step server flow (URL verify → credentials) is pinned behavior and does not change.

## Part A — Rebrand (ships independently, Brief 05 Phase 1)

### Brand block (replaces LoginScreen.kt:89 "Baby Buddy" headline)

```
┌────────────┐    72dp badge: existing launcher mark (deep-indigo bottle +
│    mark    │    cream crescent) on cream #F1E2B3, RoundedCornerShape(20.dp)
└────────────┘
   Dandle          ← Text(), headlineMedium, SemiBold — ALWAYS live text, never a baked asset
   Log the day,    ← tagline, bodyMedium, onSurfaceVariant
   one tap at a time.
```

- The badge is **fixed brand content, not theme roles** (same precedent as the diaper-color swatches): it never re-tints under dynamic color. Wordmark is live text so it inherits any M3 scheme and stays screen-reader-correct.
- Tagline (ux-designer pick): **"Log the day, one tap at a time."** (alt: "Feeds, sleep, and care — gently logged." — owner chooses).

### Background art (optional layer, must not fight dynamic color)

- ONE near-monochrome ink PNG (~1080×1080, `drawable-nodpi/`), runtime-tinted `ColorFilter.tint(onSurfaceVariant)` at **alpha 0.10 light / 0.12 dark**; bottom-anchored (~bottom 35%, fading out by 40% height), center-top clear for the form column; static (no motion); open line weights that survive low alpha. `testTag = login/background_art`. The screen must look complete without it.
- **Replicate pipeline** (per `docs/design/icons.md` gotchas): drafts on `flux-schnell`, finals on `gpt-image-2` (no transparency support → generate on solid `#808080`, key out with the existing flood-fill pipeline). No `recraft-v3` (bakes captions).
- Script: `scripts/assets/gen-login-art.sh` — calls Replicate, reads `REPLICATE_API_TOKEN` from the parent-directory env file (`~/Documents/Projects/ai-image-transformer/.env`). **The token is never committed.** Writes candidates to `docs/design/login-candidates/` (committed) and finals to `res/drawable-nodpi/`.
- **Three art directions** (render all three; owner picks; evaluate each at 10% alpha over `#FCF8FF` and `#131318` behind a mock form column first):
  - **A. Night nursery (recommended):** one large crescent moon cradling soft concentric arc ripples low in the frame — rhymes with the launcher crescent. "flat vector line art, uniform 3px stroke, one ink color, no fill, no text, no stars, no faces."
  - **B. The day, logged:** a single meandering timeline thread along the bottom with the six event glyphs (bottle, diaper, moon, note, pump, crawl) hanging like a garland — the whole product in one glance. Tightest QA: glyphs must stay legible-silhouette at background scale.
  - **C. Dandle sway:** 5–6 broad parallel arcs sweeping bottom-left→right, pure abstraction of "dandle" (to bounce gently). Lowest risk, least narrative.
  - Palette anchors: deep indigo `#2A2870`, dusk indigo `#5B5A96`, pale lilac `#C3C1FF`, cream `#F1E2B3`, optional single dusty-rose accent `#7A5368`.

### String cleanup

- `LoginScreen.kt:109` supporting text → "The address your family's Baby Buddy server lives at." (keep "Baby Buddy" only where it names the *server*).
- Dashboard header `DashboardScreen.kt:126`, About dialogs `HelpAndAbout.kt` — replace app-self references with "Dandle"; keep "Baby Buddy" where it credits the server project (licenses, mode card).
- No changes to `strings.xml` needed (login copy is composable-local by convention).

## Part B — Data-mode picker (ships with Brief 05 Phases 4–5)

### State machine (LoginViewModel)

```
MODE (landing) ──tap card──► branch
 ├─ login/mode_device ─► DEVICE_CHILD (name + birth date) ─► SignedIn
 ├─ login/mode_server ─► URL ─► CREDENTIALS   (existing flow, byte-for-byte)
 └─ login/mode_cloud  ─► CLOUD_ENTRY (Sign in | Create account) ─► SignedIn
```

- `enum LoginMode { DEVICE, SERVER, CLOUD }`, `LoginStep { MODE, DEVICE_CHILD, URL, CREDENTIALS, CLOUD_ENTRY }`.
- **Stored-session path preserved:** an expired stored *server* session lands directly on `URL` (picker never shown); device/cloud stored sessions resume as today.
- **Back rule:** every sub-flow step gets a top bar (`login/back`); back → MODE, never a sibling. Drafts survive back/forward (all in UiState). No confirmation dialogs on login.
- Cloud card appears on the picker **only when the cloud backend ships** (Brief 05 Phase 5) — no dead buttons, no "coming soon".

### Mode picker layout (full-width stacked cards — ux-designer recommendation)

- Icon + name + one-line value prop per card; `Surface` + `Row`, ≥64dp, one ripple target; tap = select **and** advance (no Continue button — back is the undo).
- Icons: `Smartphone` / `Dns` (or `HomeWork`) / `CloudLock` (the lock is the differentiator), decorative, in a 40dp `secondaryContainer` circle.
- Order (privacy gradient, Elena's thumb first, Priya one tap): device → server → cloud. No "recommended" badges, no migration-promise copy (sign-out clears local data today — never ship copy the product can't back).
- Cloud card appears only in Phase 5 (when cloud actually works) — no dead buttons. Device card ships in Phase 4.

### Sub-flow: This device only (child setup)

- "This stays on your phone." lead; **Name** (`login/child_name`, "First name is plenty.") + **Birth date** (`login/child_birth_date`, read-only field → platform DatePicker; future date = field-level error "Birth date can't be in the future."); **Start tracking** (`login/child_submit`, 56dp, disabled-reason copy in-button per the Elena convention).
- Writes one local child → `SignedIn` → dashboard with empty-timeline copy. No photo/gender/second-child here (beta restraint; second child via switcher re-entry is a follow-up brief).

### Sub-flow: Dandle Cloud

- Lead line: "Your data is encrypted before it leaves this phone."
- `SingleChoiceSegmentedButtonRow` Sign in | Create account (tab switch keeps typed values). Fields: Username (`login/cloud_username`), Password (`login/cloud_password`), Encryption passphrase (`login/cloud_passphrase`, hint "The one that unlocks your data — different from your password."), create-only Confirm (`login/cloud_passphrase_confirm`) + muted warning "If you lose this passphrase, your data can't be recovered — not even by us." (`onSurfaceVariant`, warning icon, **never** error color).
- Password + passphrase fields get visibility toggles (VM state exists but is unrendered today — fix for the server password field too while in there).
- Error states (banners reuse existing `ErrorBanner`): bad credentials → "Wrong username or password." (passphrase field stays clean); wrong passphrase → "That passphrase doesn't unlock this account's data."; username taken (create) → "That username is taken. Try another."; unreachable → "Couldn't reach Dandle Cloud — check your connection and try again."; confirm mismatch = live field error, not a banner. The protocol must map a failed envelope unwrap to exactly `WRONG_PASSPHRASE`, distinct from `BAD_CREDENTIALS`.

### New testTags (existing tags keep names — load-bearing for capture-flows)

`login/logo`, `login/background_art`, `login/back`, `login/mode_device`, `login/mode_server`, `login/mode_cloud`, `login/child_name`, `login/child_birth_date`, `login/child_submit`, `login/cloud_tab/sign_in`, `login/cloud_tab/create`, `login/cloud_username`, `login/cloud_password`, `login/cloud_passphrase`, `login/cloud_passphrase_confirm`, `login/cloud_passphrase_warning`, `login/cloud_submit`.

### Accessibility & motion

- Cards `Role.Button`, ≥48dp targets (primary buttons 56dp); back arrow contentDescription "Back"; banners polite live-region; motion budget: 200ms fade-through MODE⇄sub-flow (reverse on back), 150ms crossfade on cloud tab, **no** entrance/parallax on logo/art/cards; all gated by `ReduceMotion`.

## QA & docs deliverables (same-commit rule)

1. `scripts/qa/capture-flows.sh` + `fresh-install-login.sh`: insert `tap_tag login/mode_server` before URL typing; new shots `01-login-00-mode-picker.png`, `01-login-05-device-setup.png`, `01-login-06-cloud-create.png`; regen committed PNGs.
2. Rewrite `docs/flows/new-app/10-login.md` (picker-first flow, new tags, "How to verify"); new flow docs `11-on-device-setup.md`, `12-dandle-cloud-login.md` (spec-first: written when each phase ships).
3. `docs/qa/FEATURE-CHECKLISTS.md`: entries for picker/device/cloud per template.
4. JVM tests: LoginViewModel mode-state machine (draft preservation, back semantics, stored-session skip), cloud form validation; capture-flows assertions updated.

## Non-goals

Email/password-reset/MFA for cloud; second-child flow; store/marketing lockups; launcher-icon redesign (the existing mark is the badge); real-time sync UI affordances.

## Owner decisions requested

| # | Decision | Recommendation |
|---|----------|----------------|
| D1 | Tagline | "Log the day, one tap at a time." |
| D2 | Background art direction (A/B/C) | A (night nursery) — picked from rendered candidates |
| D3 | Whether Part A ships before Brief 05 | Yes — independent, user-visible win |
