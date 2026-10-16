# 04 — Feeding flow (all type → method combos)

## Purpose
Log a feeding with timer, amount, feed type and feeding method.

## Entry point
Dashboard → tap `Feeding` button (row 1, left; baby-bottle icon).

## Step-by-step flow
1. Tap Feeding → button active (pink); timer created: `POST api/timers/`
   → `{"id":4,"child":1,"name":"Feeding-BBapp:1",...}` (slot :1 fixed for feeding).
2. Panel content (top→bottom):
   - pink icon block (bottle)
   - `Amount` label + stepper: purple `<` button | text field (placeholder `None`) | purple `>`
     button. From "None", each `>` press steps 1.0 (3 presses → "3.0"). "None" = null amount
     (allowed for feeding).
   - live counter top-right
   - **Type selection**: initially four gray buttons in 2 rows:
     `BREAST MILK`, `FORMULA` / `FORTIFIED BREAST MILK`, `SOLID FOOD`
   - `Notes` field
   - no save button yet
3. Tap a type button → the four buttons collapse into a dropdown/spinner row showing the choice
   (e.g. "Breast milk ▼") and a **method** button row appears. Save still hidden.
4. Tap a method → method row collapses into a spinner ("Left breast ▼"); **save button (floppy)
   appears** (both type+method required).
5. Type/method can be changed via the spinners; changing type re-expands the method row and
   resets the method choice (save hides again until method re-picked).
6. Save → entry created, timer deleted, panel closes.

### Verified type → method matrices (screenshots for each)
| Type (UI) | Methods offered (UI) |
|---|---|
| Breast milk | LEFT BREAST, RIGHT BREAST, BOTH BREASTS, BOTTLE, PARENT FED, SELF FED (6, two rows) |
| Formula | BOTTLE, PARENT FED, SELF FED (3, one row) |
| Fortified breast milk | BOTTLE, PARENT FED, SELF FED (3, one row) |
| Solid food | BOTTLE, PARENT FED, SELF FED (3, one row) — **includes BOTTLE**, unlike the research doc |

Type dropdown options (exact order): "Breast milk", "Formula", "Fortified breast milk",
"Solid food".

## Server effect (verified)
- Save: `POST api/feedings/` → id=10 `{child:1, start:"2026-10-16T21:12:11Z" (== timer start),
  end:"2026-10-16T21:15:22Z", type:"formula", method:"bottle", amount:3.0, notes:""}`
  + `DELETE api/timers/4/`. feedings 9→10.
- API enum values observed: type `formula`; method `bottle`; (seed data: `breast milk` +
  `left breast`).

## Screenshots (docs/screenshots/existing-app/04-feeding/)
- `01-panel-initial.png` — amount stepper ("None"), 4 type buttons, notes, no save
- `02-type-breastmilk-methods.png` — Breast milk → 6 method buttons
- `03-left-breast-selected.png` — both spinners collapsed ("Breast milk ▼" / "Left breast ▼"),
  save visible
- `04-type-spinner-dialog.png` — type dropdown open with 4 options
- `05-type-formula-methods.png` — Formula → 3 methods
- `06-ready-to-save.png` — Amount 3.0, Formula + Bottle selected, save visible
- `07-type-fortified-methods.png` — Fortified breast milk → 3 methods
- `08-type-solidfood-methods.png` — Solid food → 3 methods (incl. BOTTLE)

## Replication notes for the new app (MUST match)
1. Two-stage selection: buttons first, collapse into spinners after choice; save appears only
   when type+method are both set.
2. Method matrix above — note Solid food DOES include Bottle in v2.6.4.
3. Amount editor: `<`/`>` big buttons flanking the value, step 1.0 near zero, "None" placeholder
   means null amount (feeding allows null).
4. Timer name `Feeding-BBapp:1`; save posts type/method/amount with the timer's exact start.
5. Panel icon block stays the bottle icon regardless of selection (observed).
