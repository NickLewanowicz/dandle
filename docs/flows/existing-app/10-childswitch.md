---
title: "Original app: Child switching"
---

# 10 — Child switcher & per-child history

## Purpose
Switch between children; each child gets an isolated dashboard + history.

## Entry point
Horizontal swipe anywhere on the dashboard (ViewPager `babyViewPagerSwitcher`). There is NO
picker/dropdown — swiping is the only mechanism (verified: toolbar title changes, no menu
involved).

## Step-by-step flow
1. On Milo's page, swipe right→left (finger from right edge toward left) → next child page
   ("Zoe Rivera" in toolbar). Swipe left→right returns to the previous child.
2. Each page has its OWN state: six buttons (all inactive unless that child has running timers),
   own timeline (Zoe showed 5 seeded entries incl. yesterday's), own open panels/drafts.
3. Selected child is remembered: after logout + fresh login the app re-opened on **Zoe** (the
   last selected child) — selection survives logout and process death.

## Server effect
Children list fetched via `GET api/children/` (id 1 Milo Rivera `milo-rivera`, id 2 Zoe Rivera
`zoe-rivera` in this env). Timers/timeline are filtered by the child id (verified: a sleep timer
started on Zoe's page was created with `child:2`). Adding/renaming children is web-UI-only; the
app is read-only for children.

## Screenshots (docs/screenshots/existing-app/10-childswitch/)
- `01-zoe-page.png` — Zoe's page after swiping from Milo (sparse history)

## Replication notes for the new app (MUST match)
1. Horizontal swipe = child switch; no other affordance; toolbar shows current child's name.
2. Per-child page state (buttons/panels/drafts/timeline) is isolated.
3. Selected child persists by slug across restarts AND logout.
4. Timer creation uses the CURRENT page's child id.
