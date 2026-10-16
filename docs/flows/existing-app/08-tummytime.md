# 08 — Tummy time flow

## Purpose
Log a tummy-time session with timer + optional milestone text.

## Entry point
Dashboard → tap `Tummy time` button (row 2, right; crawling-baby icon).

## Step-by-step flow
1. Tap Tummy time → button active (green); timer created (name slot `Tummy time-BBapp:3`).
2. Panel: green icon block (crawling baby), `Notes` field (doubles as the **milestone** input),
   live counter, floppy save visible immediately (no required fields).
3. Type milestone text, save → entry created, timer deleted, panel closes. Timeline line:
   "M/D/YY h:MM - h:MM (0:01)" + milestone text "Rolled over!" on the second line.

## Branches / states
- No dedicated milestone field/multi-select — just the Notes text (stored server-side in the
  `milestone` field, NOT `notes`).
- Cancel semantics identical to other timers.

## Server effect (verified)
`POST api/tummy-times/` → id=3 `{child:1, start:"2026-10-16T21:21:27Z",
end:"2026-10-16T21:22:13Z", milestone:"Rolled over!"}` + timer deleted. tummy-times 2→3.

## Screenshots (docs/screenshots/existing-app/08-tummytime/)
- `01-panel.png` — green panel, counter 0:00:09, Notes field, floppy save

## Replication notes for the new app (MUST match)
1. The panel note text must be posted to the `milestone` field of `api/tummy-times/` (not
   `notes`) — timeline renders it as the second line.
2. Timer name slot :3; save/end semantics same as sleep.
