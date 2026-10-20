---
title: "Owner UI rework"
---

# Owner UI rework — triage & decisions (2026-10-18)

Direct owner feedback, one pass. Every item is **decided here** (owner pre-approved the
direction in the feedback itself); deltas land in spec v0.4. Reference for the running-timer
look: `docs/screenshots/existing-app/03-sleep/01-panel-running.png` — the original app renders a
running timer as an **expanded top element of the timeline**: tall per-type-colored icon tile on
the left, large `H:MM:SS` counter top-right, optional note field mid-card, **icon-only save**
(floppy) bottom-right. No ✕, no "Running" word, no "Save" word.

| # | Owner feedback | Decision |
|---|---|---|
| U1 | Buttons must NEVER move — nothing dynamic above the grid | Dashboard order: header → **quick actions grid (fixed)** → dynamic content (timer card / timeline) → timeline. The `ActiveTimersStrip` above the grid is deleted. |
| U2 | Running timer shows below the buttons like the diaper card did; mimic the original app's expanded-element look | `RunningTimerCard` as the timeline's FIRST list item (it scrolls with the list; the keyboard can never hide the field being typed into; the list scrolls to reveal it when a timer starts): full-height **type-colored icon tile** left, `H:MM:SS` counter top-right, type fields inline (Sleep: note · Pumping: amount+note · Tummy: milestone+note · Feeding: type/method/amount), compact "Started Xm ago · Adjust" line (T2 retained), **icon-only check save** bottom-right. |
| U3 | ✕ must not exist as a primary action; the original used iconography; "Save"/"Running" words dropped | Card has **no ✕** and no word labels on actions. Save = check icon (`timerSave` tag unchanged). Discard path = tap the type button again → existing StopGuard (>10s dialog Save/Discard/Keep; ≤10s silent discard) — exactly the original's tap-to-stop semantics. |
| U4 | Diaper and Note creation are edit-like actions → open a **sheet**, same UI as editing a completed event | `CreateEntrySheet` (modal bottom sheet): icon+title row, the SAME field composables as Quick Edit, a TIME row (native pickers, defaults to now), Save, ✕. No delete/retry. Feeding/Sleep/Pumping/Tummy stay inline cards (owner likes the feeding card). |
| U5 | Keep the feeding card's type/method/amount look | Adopted into the RunningTimerCard frame (fields unchanged). |
| U6 | ±1m/±15m steppers in Quick Edit are non-standard — use the native date/time interface, pre-populated, "that is all that should be there" | `StepperTimeRow` deleted. Each time row = the stored value ("Today 14:32", tap → native **TimePicker** pre-populated) + a small calendar button (→ native **DatePicker**, then TimePicker). Start moves stay duration-preserving. Panels' "Started Xm ago · Adjust" (T2) is retained in compact form on timer cards only. |
| U7 | Failed entries: no duplication at the top of the page; the stacked badge makes rows oddly tall; put the caution icon over the primary icon and explain on tap | `SyncChipState.Failed` removed (chip keeps Offline/Syncing/All-synced). Timeline FAILED rows: small warning dot **overlaid on the type icon's corner**, normal row height; row tap → failure dialog (existing). |
| U8 | Note content as a plain headline can collide with event labels ("Feeding interrupted") — differentiate it; notes on other events should match | NOTE headlines render **italic**; note previews on other event types also italic. Icon + italics = unambiguous "this is words, not a structured event". |
| U9 | Drop the "Baby Buddy" title; child selector moves to the left like the original app (name in the corner); less primary-colored; Slack-style switcher; open to pushback | Header: **child first name + chevron** (plain text, no chip background) on the left — tap opens the full-name dropdown; overflow (⋮) right; sync chip inline (only when relevant). Pushback note: we keep it a text+chevron rather than Slack's colored workspace tile — one child is the norm and a tile would shout. |
| U10 | Widgets: 2×2 has spare space — show subdued "last" time under tiles (e.g. 1h30m); running timer shows elapsed and changes color per type | QuickActions slots get a second subdued line: `last` since-text ("1h30m", "45m") when idle; running slots show the elapsed counter in the **type color** accent instead of gray/generic primary. CareDashboard unchanged in this rework (already shows last times) — but persona round 2 (2026-10-18) subsequently changed it: running timer renders as the widget's FIRST row ("Asleep since HH:MM · Xm elapsed"), all info rows open the app (timers never started from the widget), feeding rows gained method-specific icons, and row timestamps follow the >5-minute range rule. |
| U11 | Brief 03 remainder folded in | `TypeColors.kt`: fixed per-type container/onContainer pairs (light+dark, M3 tone rules, Brief 03 §3); timeline icon chips + running card tiles + widget running accents tinted; feeding/pumping breast-side badge (L/R/Both) on timeline icons — feeding side already lives in `method`; pumping side = notes prefix `[Left]/[Right]/[Both]` (server has no side field), selector on pumping cards/sheet, rendered as the badge with the prefix stripped from visible text. |

**Non-goals (restraint):** no new screens, no motion beyond existing 150ms fades, no
re-architecture of the outbox/panel draft machinery — the rework is presentational plus the
tap-semantics change in U3 and the create-time override in U4.

**Verification:** every flow re-captured by `scripts/qa/capture-flows.sh` (PNGs committed in the
same change); canaries A1/A2 re-asserted; overlap scenario rewritten for the picker-based edit
sheet; widget captures show the last-line + running accent.
