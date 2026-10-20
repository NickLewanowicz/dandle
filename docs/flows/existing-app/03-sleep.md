---
title: "Original app: Sleep"
---

# 03 — Sleep timer flow

## Purpose
Log a sleep period with a live server-side timer.

## Entry point
Dashboard → tap `Sleep` button (row 1, right; crescent-moon "Zzz" icon).

## Step-by-step flow
1. Tap Sleep → button switches to active art (lavender); panel opens inline with:
   - lavender icon block (moon) on the left
   - live counter `H:MM:SS` top-right (0:00:01 → updates ~2x/s)
   - `Notes` single-line field
   - save button = **floppy-disk icon**, bottom-right of the panel (enabled immediately — sleep
     has no required fields)
2. A server timer is created on first tap: `POST api/timers/` → observed
   `{"id":1,"child":1,"name":"Sleep-BBapp:2","start":"2026-10-16T21:03:35.295000Z"}`.
   The name slot `:2` is fixed for sleep (feeding=1, tummy=3, pumping=4).
3. Optionally type a note (auto-draft; stored locally until save).
4. Tap the floppy button to save.

## Branches / states
- **Tap Sleep again while running**:
  - timer age ≤ ~10 s → **silent cancel**: timer deleted, nothing logged, panel closes.
  - timer age > ~10 s → **"Stop timer?" dialog**: "The sleep timer is running for a long time
    already. Do you really want to stop it or keep it running?" with `KEEP` | `STOP`.
    - `KEEP`: dialog closes, timer keeps running (verified server-side).
    - `STOP`: timer deleted, nothing logged.
- **Restart**: tapping Sleep again after cancel creates a NEW timer with the same name
  (`Sleep-BBapp:2`), fresh start time.
- **App relaunch with running timer**: panel is already open with correct elapsed time
  (verified twice: 0:01:52 after process kill; 0:01:09 after backgrounding) — the timer lives on
  the server, nothing timer-related is stored client-side.

## Server effect (verified)
- Start: `POST api/timers/` (name `Sleep-BBapp:2`).
- Save: `POST api/sleep/` → id=6 `{child:1, start:"2026-10-16T21:03:35Z" (== timer start),
  end:"2026-10-16T21:07:52Z", notes:"QA sleep entry"}` AND `DELETE api/timers/1/` — save stops
  (deletes) the server timer. sleep count 5→6.
- Cancel: `DELETE api/timers/{id}/` only.

## Screenshots (docs/screenshots/existing-app/03-sleep/)
- `01-panel-running.png` — panel with counter 0:00:46, Notes field, floppy save
- `02-panel-with-keyboard.png` — Notes focused (teal underline), keyboard open
- `03-note-typed.png` — "QA sleep entry" typed, counter 0:04:07
- `04-interrupted-by-other-app.png` — environment glitch (another app was installed mid-test);
  documents that the session was interrupted, not app behavior
- `05-resumed-running-timer.png` — panel restored after relaunch, counter 0:01:52
- `06-stop-timer-dialog.png` — "Stop timer?" with KEEP / STOP

## Replication notes for the new app (MUST match)
1. Counter format `H:MM:SS`, live from the server timer's start.
2. Save = floppy icon, always enabled for sleep; appears bottom-right inside the panel.
3. Panel contains ONLY the counter + Notes (+ icon block). No fields for naps etc.
4. The 10-second guard dialog with exact texts "Stop timer?" / "The sleep timer is running for a
   long time already. …" / KEEP / STOP; ≤10s cancels are silent and immediate.
5. Save payload: `start` = timer start (exact), `end` = save-time, `notes` = panel note.
   Timer is deleted server-side as part of saving.
