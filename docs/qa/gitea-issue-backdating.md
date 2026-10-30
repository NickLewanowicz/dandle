# Backdating entries: bring back a quick time-adjust path for instant models

## Context

The Dandle quick-capture redesign (framework v2.3) removed the **Time** row from the
in-progress diaper and note form cards. Time now defaults to save-time; corrections
happen through the edit flow (tap a history row → edit sheet → change the time with
the native picker).

This was a deliberate scope reduction for the quick sheets: the original app's sheet
carried a native time picker row on every log, which added height and friction to the
common "it's happening right now" case that the redesign optimizes for.

## The gap

Backdating is a real flow — "the diaper actually happened 20 minutes ago" — and it
now requires: save normally → find the row in History → tap → edit sheet → change the
time. That's more steps than the original app for a case that isn't rare.

## Proposal

Evaluate a lighter backdate affordance on the form cards that doesn't restore the
always-on picker row:

- A small "happened earlier" toggle/link on the card; when tapped, it reveals the
  native time picker (hidden again once set, showing a "logged for 2:14 pm" summary
  chip with a clear affordance).
- Or: after saving, deep-link straight into the edit sheet for the just-saved entry.

Either keeps the common case at zero extra fields while preserving a fast correction
path.

## References

- Spec: `docs/flows/new-app/00-functional-spec.md` §3 (diaper quick sheet) — update
  when implemented.
- Original app behavior: time picker row always present on the diaper sheet
  (`docs/flows/existing-app/05-diaper.md`).
- Framework: `docs/design/candidates/dandle-ux-v3/mocks.html` — Design framework v2.3
  ("choices cascade; completed choices collapse" suggests the backdate toggle pattern).
