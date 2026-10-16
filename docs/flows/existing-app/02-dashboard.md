# 02 — Dashboard (all-in-one main screen)

## Purpose
Single screen per child: start/stop logging activities and review the child's recent history.

## Entry point
Immediately after login. Persists across app restarts (token stored). BACK from here exits the app.

## Layout (verified from accessibility tree + screenshots)
- **Toolbar**: child's full name ("Milo Rivera" / "Zoe Rivera"), right side 3-dot
  "More options" (ImageView, contentDescription "More options").
- **`babyViewPagerSwitcher`** — horizontal pager, one page per child (see 10-childswitch.md).
  Page content (inside `mainScrollView`, one ScrollView per child):
  1. `newTimerButtons` — six 176x176px (device px) icon buttons in a centered 2x3 grid:
     Row 1: `feedingDisabledButton` (bottle), `diaperDisabledButton` (diaper),
            `sleepDisabledButton` (moon Zzz)
     Row 2: `notesDisabledButton` (note+pencil), `pumpingDisabledButton` (breast pump),
            `tummyTimeDisabledButton` (crawling baby)
     (resource ids keep the "Disabled" suffix in both visual states; active state swaps the
     drawable to the colored variant.)
  2. `loggingEditors` — inline panel inserted here while an activity is open.
  3. "Timeline" separator (`timelineTitle` + `leftTimelineTitleLine`/`rightTimelineTitleLine`).
  4. `innerTimeline` — vertical list of entry rows. Row anatomy (per type):
     - `longclickBubble` (full-width background used by the long-press animation)
     - type container (`noteTimeView`, `feedingView` (+`feedingBottleImage` /
       `feedingBreastLeftImage` etc.), `diaperView` (+`diaperWetImage`/`diaperSolidImage`
       +`diaperColorPreview`), `pumpingTimeView`, `sleepView`, `tummyTimeView`)
     - one text view per type (`noteTimeEntryText`, `feedingText`, `diaperText`, `sleepText`,
       `pumpingTimeNotes`, `tummytimeMilestoneText`)
     - `removeButton` (red trash, right side, always visible)
- No bottom navigation / drawer / FAB. No timer summary row when idle: a running timer shows only
  as the highlighted button + its open panel.

## Behaviors
- Tap quick-action button → toggles the panel; timer activities create a server timer
  (`POST api/timers/`) within ~1 s (see per-activity docs 03–08).
- Timeline row: plain tap = no-op (verified); long-press = open in browser (see 09-history.md);
  trash = delete with confirm (see 09-history.md).
- Live counter in open timer panel updates every ~500 ms; **this continuous animation makes the
  accessibility tree go stale** — `uiautomator dump` fails with "could not get idle state" and
  returns cached content while any timer panel is open (tooling quirk worth knowing for the new
  app's test automation; see 99-discrepancies).

## Screenshots (docs/screenshots/existing-app/02-dashboard/ and others)
- `02-dashboard/01-initial-dashboard.png` — Milo page with six buttons + timeline + long-press
  tutorial bubble overlay ("Long press a timeline entry to navigate directly to the corresponding
  babybuddy page in your web browser.")
- `03-sleep/01-panel-running.png` — dashboard with an open sleep panel (layout shift)
- `10-childswitch/01-zoe-page.png` — same screen for the second child

## Replication notes for the new app (MUST match)
1. Toolbar title = child first+last name; only global control is the 3-dot menu.
2. Six buttons in 2x3 centered grid, exact order Feeding / Diaper / Sleep / Note / Pumping /
   Tummy time; active vs inactive art variants.
3. Panel opens INLINE between buttons and timeline (content pushed down; page does not navigate).
4. Timeline rows: icon | text | red trash; reverse-chronological; no day headers.
5. One-time tutorial bubbles: "check out the help in the main menu" (login) and the long-press
   hint (first dashboard render).
6. Selected child and open panels survive process death (server timers re-appear with correct
   elapsed time).
