# 05 — Diaper change flow (event, no timer)

## Purpose
Instantly log a diaper change (wet/solid, optional color + amount + note).

## Entry point
Dashboard → tap `Diaper change` button (row 1, middle; diaper icon).

## Step-by-step flow
1. Tap Diaper → button active (yellow); panel opens with NO timer and NO counter:
   - yellow icon block (diaper)
   - two large toggle buttons, initially "off" art: crossed-out water drop ("no wet") and
     crossed-out poop ("no solid")
   - a `+` button (expander) to the right
   - `Notes` field
   - **no save button** — it appears only when at least one toggle is ON
2. Tap the wet toggle → green background with plain water-drop icon (on state); floppy save
   button appears at the panel's bottom-right.
3. Tap `+` → expander becomes `-` and extra options appear between notes and save:
   - **Color picker**: horizontal carousel of square swatches — black, brown/amber, green,
     yellow, and an X ("none") — with small up/down scroll arrows at the right edge; a "Color"
     label with dropdown caret.
   - **Amount**: `<` / value ("None") / `>` stepper (same editor as feeding).
4. Save (floppy) → immediate `POST api/changes/`, panel closes, entry appears in timeline.

## Branches / states
- Save disabled/hidden until ≥1 of Wet/Solid is on (verified visually).
- Both toggles can be on simultaneously (seed data contains wet+solid entries).
- Color and amount are optional extras (apply regardless of wet/solid).
- Draft persistence: toggles/notes survive navigation; cleared after successful save
  (panel closed and reset after save — verified).

## Server effect (verified)
`POST api/changes/` → id=8 `{child:1, time:"2026-10-16T21:18:34Z" (single instant, no range),
wet:true, solid:false, color:"brown", amount:null, notes:""}`. changes 7→8.
Color is stored even for a wet-only change (matches app behavior).

## Screenshots (docs/screenshots/existing-app/05-diaper/)
- `01-panel-initial.png` — crossed-out wet/solid toggles, `+`, notes, no save
- `02-wet-selected.png` — wet ON (green), save button visible
- `03-extra-options.png` — expanded: color carousel (black/brown/green/yellow/X), amount stepper
- `04-after-save.png` — panel closed/reset; new entry "wet + brown swatch 5:18 PM" at top of
  timeline

## Replication notes for the new app (MUST match)
1. No timer, no counter; entry `time` = save moment (single instant).
2. Wet/Solid as icon toggles with on/off art (crossed-out = off, green = on); save hidden until
   one is on.
3. "+" expander hides color + amount by default; color values map to
   ""/black/brown/green/yellow (X = none/"").
4. Save is silent and immediate (no confirmation dialog).
