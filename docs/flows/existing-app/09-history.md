# 09 — History / timeline

## Purpose
Review, open-on-web, and delete recent entries for the selected child.

## Entry point
Always visible on the dashboard below the "Timeline" separator.

## Content & layout
- One merged reverse-chronological stream of all 6 implemented types (sleep, feedings, changes,
  pumping, notes, tummy-times). **No grouping by day** — a continuous list. Entries from
  different days appear inline ("10/16/26 …", "10/15/26 …").
- Row content per type:
  - Sleep: lavender moon icon; "10/16/26 12:24 PM - 1:04 PM (0:40)"; note on 2nd line if set.
  - Feeding: pink icon varying by method (bottle / left breast / …); "10/16/26 4:24 PM - 4:40 PM
    (0:16)" + "Amount: 110" on 2nd line (amount ".0" stripped: seeded 3.0 renders "Amount: 3").
  - Diaper: yellow diaper icon + wet-drop and/or poop mini-icons + color swatch square for
    non-empty colors (brown observed); "10/16/26 3:24 PM".
  - Pumping: magenta pump icon; range + "Amount: 70".
  - Note: teal note icon; time + note text on 2nd line.
  - Tummy time: green crawl icon; range + milestone text on 2nd line.
- Duration "(H:MM)" shown for entries ≥ ~1 min.
- Right side: red trash `removeButton` per row, always visible.
- Scrolling: plain vertical scroll; list is long enough that older entries load as you scroll
  (infinite scroll with background fetches — seeded 25+ entries all rendered while scrolling).

## Behaviors (verified)
- **Plain tap on a row: nothing happens** (no detail screen, no selection).
- **Long-press (~3 s) on a row**: leaves the app — `ACTION_VIEW` on
  `{server}/{uiPath}/{id}/` (UI paths: `sleep/6/`, `feedings/10/`, `changes/8/`, `pumping/3/`,
  `notes/4/`, `tummy-time/3/`). Verified: long-press on the QA sleep entry opened Chrome at
  `10.0.2.2:8000/login/?next=/sleep/6/` (web session isn't shared with the browser, so the web
  login wall appears; `next` proves the target path). A one-time tutorial bubble explains this:
  "Long press a timeline entry to navigate directly to the corresponding babybuddy page in your
  web browser." (visible in 02-dashboard/01-initial-dashboard.png).
- **Delete**: tap trash → dialog "Delete entry" / "Are you sure you want to delete the notes
  entry from 10/16/26 5:20 PM - 5:20 PM?" with `CANCEL` | `DELETE` → `DELETE api/notes/4/`
  verified (count 4→3) and row removed from the timeline.
- New entries are inserted at the top immediately after a save.

## Server effect
Only delete issues a request (`DELETE api/<type>/<id>/`). Loading uses paginated GETs of the six
list endpoints merged client-side.

## Screenshots (docs/screenshots/existing-app/09-history/)
- `01-scrolled.png` — scrolled list, mixed types/days, trash buttons
- `02-longpress-browser.png` — Chrome first-run launched by the long-press intent
- `03-chrome-signin.png` — Chrome sync screen (transient)
- `04-browser-target-url.png` — URL bar `10.0.2.2:8000/login/?next=/s…` proving `/sleep/6/`
- `05-after-plain-tap.png` — after plain tap: no change (also shows full timeline with QA entries)
- `06-delete-confirm-dialog.png` — "Delete entry" CANCEL/DELETE dialog

## Replication notes for the new app (MUST match)
1. Tap = no-op; ONLY long-press opens the web page (`{server}/{uiPath}/{id}/`, note
   `tummy-time` singular vs API `tummy-times`).
2. Delete = per-row trash + confirmation dialog with exact texts above.
3. No day grouping; merged stream; duration format `(H:MM)`; "Amount: N" second line; color
   swatch + wet/solid mini-icons for diapers; method-specific icons for feedings.
4. Entries appear at top immediately after save (no full reload needed visually).
