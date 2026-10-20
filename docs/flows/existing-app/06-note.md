---
title: "Original app: Note"
---

# 06 — Note flow (event, no timer)

## Purpose
Log a free-text note.

## Entry point
Dashboard → tap `Note` button (row 2, left; note+pencil icon).

## Step-by-step flow
1. Tap Note → button active (teal); minimal panel: teal icon block + `Notes` text field only.
2. Tap the field (it did not auto-focus/keyboard in this run — tap required), type text.
3. Save button (floppy) appears once text is non-empty, bottom-right of the panel.
4. Save → immediate `POST api/notes/`, panel closes, entry appears in timeline
   (icon + "M/D/YY h:MM AM/PM" + note text on second line).

## Branches / states
- Empty text → no save button.
- Draft persists while panel open (app restart included, per draft-store behavior); cleared on
  successful save.

## Server effect (verified)
`POST api/notes/` → id=4 `{child:1, time:"2026-10-16T21:20:10Z", note:"QA note from flow test"}`.
notes count 3→4 (later deleted in the delete-flow test, back to 3).

## Screenshots (docs/screenshots/existing-app/06-note/)
- `01-panel-focused.png` — minimal panel (icon block + Notes field, no save)
- `02-note-typed-save-visible.png` — "QA note from flow test" with focused teal underline and
  floppy save

## Replication notes for the new app (MUST match)
1. Single text field; save appears only when non-empty; immediate save, no confirmation.
2. Entry uses `note` + `time` fields (not start/end).
