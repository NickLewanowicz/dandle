---
title: "Flow verification: Help & about"
---

# 60 — Help / About / overflow (spec §9) — QA flow doc

**Purpose:** verify the overflow menu contents and that Help (slideshow) and About (version, licenses, upstream links) open and render.

## How to verify

- Tags: `dashboard/overflow`; menu items are plain text nodes (`Sign out`, `Help`, `About` — no dedicated tags); About dialog text nodes; Help slide titles + `Next`/`Done` buttons.
- Steps: app → `ui-tap-tag.sh dashboard/overflow` → screenshot menu → tap `Help` → walk slides via the `Next` button bounds → `Done` → overflow → `About` → screenshot.
- Expected server requests: none (pure UI).

## Observed

- Overflow menu: **Sign out**, **Help**, **About** — exactly the §9 set ✓ (sign-out flow itself not exercised in this pass; checklist 8 covers it).
- **Help:** 4-slide modal slideshow — "Six buttons", "Timers", "Offline first", "Widgets" — plain parent-language copy, page dots, Next/ Done ✓ (spec §9 "short slideshow", UX §2 motion restraint: static slides). Advancing via Next works; Done closes.
- **About:** dialog with version (**Baby Buddy Next 0.1.0**), one-paragraph description, **Upstream** section crediting Baby Buddy server (BSD-2-Clause, github.com/babybuddy/babybuddy) and Baby Buddy for Android (MIT, © 2022 Paul Konstantin Gerke), plus the independent-client/no-copied-assets note ✓ per §9.
- Both open over the dashboard and dismiss cleanly (OK/Done/BACK).

## Branches / states

| Branch | Result |
|---|---|
| Overflow open | 3 items, correct order |
| Help | 4 slides, Next/ Done |
| About | Version + licenses + links |
| Sign out | Not exercised this pass (checklist 8) |

## Screenshots

- `docs/screenshots/new-app/60-help-about/01-overflow-menu.png`
- `docs/screenshots/new-app/60-help-about/02-help.png` (slide 1)
- `docs/screenshots/new-app/60-help-about/03-help-page2.png` (Timers)
- `docs/screenshots/new-app/60-help-about/04-help-last-page.png` (Widgets/Done)
- `docs/screenshots/new-app/60-help-about/05-about.png`
- `docs/screenshots/new-app/60-help-about/06-final-dashboard-state.png` (post-QA demo state)

## Deviations from spec

- **P3** — finding #9: About lacks the read-only "Connection" section (server URL + username) required by functional spec §9 / UX spec §2.
