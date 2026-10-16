# 07 — Pumping flow

## Purpose
Log a pumping session with timer + amount.

## Entry point
Dashboard → tap `Pumping` button (row 2, middle; breast-pump icon).

## Step-by-step flow
1. Tap Pumping → button active (magenta); timer created (name slot `Pumping-BBapp:4`).
2. Panel: magenta icon block, `Amount` label + `<`/`>` stepper with value **defaulting to "0.0"
   (NOT "None" — pumping amount is not nullable)**, `Notes` field, live counter, and the floppy
   save button visible immediately (0.0 is a valid value).
3. `>` increments by 1.0 (1 press → 1.0).
4. Save → entry created, timer deleted, panel closes. Timeline line:
   "M/D/YY h:MM - h:MM (0:01)" + "Amount: 1" (`.0` stripped).

## Branches / states
- Cancel behaves like all timer activities (<10 s silent, >10 s "Stop timer?" dialog).
- Amount cannot go below 0 / cannot be null.

## Server effect (verified)
`POST api/pumping/` → id=3 `{child:1, start:"2026-10-16T21:20:37Z", end:"2026-10-16T21:21:07Z",
amount:1.0}` + timer deleted. pumping 2→3.

## Screenshots (docs/screenshots/existing-app/07-pumping/)
- `01-panel-initial.png` — Amount 0.0 default, save visible, counter 0:00:06

## Replication notes for the new app (MUST match)
1. Amount defaults to 0.0 and is required-but-satisfiable (never null) — contrast with feeding
   ("None").
2. Timer name slot :4; payload uses start/end + amount (v2 API `pumping/`).
