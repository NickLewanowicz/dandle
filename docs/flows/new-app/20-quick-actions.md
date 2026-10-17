# 20 — Quick actions: all six create flows (spec §3) — QA flow doc

**Purpose:** drive all six quick actions end-to-end on the emulator and verify each save server-side (endpoint + fields), plus the timer stop-guard, the type→method cascade, per-child learned defaults, and the duplicate guard.

## How to verify

- Login (see `10-login.md`), then drive by tag: `scripts/qa/ui-tap-tag.sh <tag>` (fresh dump before every tap).
- Tags: `dashboard/quick_action/{FEEDING,CHANGE,SLEEP,NOTE,PUMPING,TUMMY_TIME}` (enum names — diaper = `CHANGE`), `dashboard/panel/*` (`feeding_type/<wire>`, `feeding_method/<wire>`, `color/<wire>`, `amount`, `wet`, `solid`, `extra_options`, `text`, `milestone`, `save`, `close`), `dashboard/timer_strip`, `dashboard/timer/<TYPE>` + `/save` + `/discard`, `dashboard/stop_dialog/{save,discard,keep}` and `dashboard/duplicate_dialog/{add}` (the latter two are defined in TestTags.kt but do NOT appear in the a11y tree — QA finding #8; drive by `bounds_by_text`).
- Server: `scripts/qa/api.sh GET /api/{feedings,changes,sleep,notes,pumping,tummy-times,timers}/`, `scripts/qa/server-log.sh`.
- Expected server requests: timer types `POST /api/timers/` (named `<Type>-BBapp:<n>`) on start; on save `POST /api/{feedings|sleep|pumping|tummy-times}/` + `DELETE /api/timers/<id>/`; diaper `POST /api/changes/`; note `POST /api/notes/`.

## Observed

### Feeding (timer)
1. Tap `FEEDING` → timer starts: server `POST /api/timers/` 201 (`Feeding-BBapp:1`), running card in strip (`00:00:0x` ticking), panel opens below the grid.
2. Cascade: type **Solid food** → method chips reduce to Bottle/Parent fed/Self fed, method resets to **Bottle** ✓ (spec §3.1, verified in dump: `feeding_method/bottle checked=true`).
3. Type **Formula** → same 3-method cascade. Amount typed `120`.
4. Save → `DELETE /api/timers/` 204 + `POST /api/feedings/` 201. Server entry: `{type: "formula", method: "bottle", amount: 120.0, start = timer start, end = stop}` — exact.
5. Learned defaults: reopen FEEDING for Milo → `formula` + `bottle` pre-checked ✓. Switch child to **Zoe** → panel defaults `breast milk` + `bottle` (fallback), full 6-method cascade → per-child independence ✓ (spec §3.1, UX §3).

### Diaper (no timer)
1. Tap `CHANGE` → panel, **Save disabled** (dump: `enabled=false`) until a toggle is on ✓.
2. Wet + Solid on, `extra_options` disclosure → Color **yellow**, amount `40` → Save → `POST /api/changes/` 201 `{wet:true, solid:true, color:"yellow", amount:40.0}` ✓ (endpoint is `/api/changes/` ✓).
3. Duplicate guard: second diaper save ~1 min later → dialog **"Diaper change logged 1 min ago. Add anyway?"** with **Add** as the filled default and "View last entry" secondary; Add → entry saved. Guard re-fired at 30 s ("logged just now") and at 2 min 05 s ("logged 2 min ago") — window consistent with spec §3.7's 4 minutes.

### Sleep (timer)
1. Tap `SLEEP` → server timer `Sleep-BBapp:n` + strip card. Note field typed "QA night nap".
2. Card **Save** (direct) after 21 s → `POST /api/sleep/` 201 `{start, end, notes:"QA night nap}`, `nap` not sent (server-computed `false`) ✓, timer DELETEd ✓.
3. Stop guard: strip `✕` after 1 h 07 m (adopted orphan timer) → dialog **"Stop timer? You've been tracking Sleep for 1:07:23."** — **Save filled**, Keep/Discard subdued ✓ (UX §3). Discard → `DELETE /api/timers/` 204, no entry created ✓.
4. <10 s silent discard: start, discard at 5 s → no dialog, no entry, timer deleted ✓ (spec §3 common rules).
5. Tap `SLEEP` while a timer runs → focuses/opens the running timer instead of starting a duplicate ✓ (verified with the restored orphan timer, spec §3).

### Notes
Tap `NOTE` → multiline field, auto-focused; typed → Save enables → `POST /api/notes/` 201 `{note, time=now}` ✓. Save disabled when empty (verified in offline run before typing).

### Pumping (timer)
Start → amount `85` → card Save after 17 s → `POST /api/pumping/` 201 `{amount: 85.0, start, end, duration}` ✓.

### Tummy time (timer)
Start → milestone "Grabbed toy" → card Save after 19 s → `POST /api/tummy-times/` 201 `{milestone:"Grabbed toy", start, end}` ✓.

### Cross-cutting
- Timer restore after process death: feeding timer started, app force-stopped, relaunched → strip + panel restored, elapsed wall-clock-correct ✓ (spec §3).
- Timer adoption: an orphan server timer (left by a previous session) is adopted at app start (`BB/TIMER restore SLEEP serverId=9`), shows correct wall-clock elapsed, and can be discarded (DELETE) ✓.
- Draft persistence: values set in the diaper panel survived a panel close + reopen (saved payload contained all draft fields) ✓ (UX §3).

## Branches / states

| Branch | Result |
|---|---|
| Timer ≤10 s stop | Silent discard |
| Timer >10 s stop | Guard dialog, Save emphasized |
| Duplicate within minutes | Guard dialog, Add default; never blocks |
| Already-running timer, button re-tap | Focuses running timer |
| Second child | Independent feeding defaults |
| Offline save | Queued (see `40-offline-sync.md`) |

## Screenshots

`docs/screenshots/new-app/20-quick-actions/01…25` — running panel, solid-food cascade, formula ready-to-save, diaper toggles/extra options, guard dialogs, stop dialog (Save emphasized), sleep saved, note panel, defaults for Milo/Zoe, child persistence.

## Deviations from spec

- **P1** — saved entries do not appear in the timeline in real time (see `99-qa-findings.md` #1/#2). The create flows themselves and all server payloads are correct.
- **P2** — panel `✕` (close) does not run the stop guard; a running timer keeps running silently after the panel is closed (UX §3 lists the panel's ✕ as a stop-guard trigger). Finding #4.
- **P3** — diaper headline renders "Wet + Solid (Yellow) ● Yellow"; §4.1 wants "Wet + Solid" + color chip without the parenthetical. Finding #6.
- **P3** — `dashboard/stop_dialog*` / `dashboard/duplicate_dialog*` tags promised in FEATURE-CHECKLISTS 2/4 are absent from the a11y tree. Finding #8.
