---
title: "QA findings"
---

# 99 — QA findings — beta acceptance pass (2026-10-16)

QA engineer pass on emulator-5554, app `net.babybuddy.next.debug`, server `babybuddy` @ `http://10.0.2.2:8000`. Full pass over checklists 1–10 (see `10-login.md` … `60-help-about.md`). Known/triaged items from the engineer handoff (widget-picker preview, days-old "Xd" staleness) are NOT re-filed.

## Verdict

**NOT shippable as beta — 3 open P1.** The create/save/edit/sync engine is correct at the data layer (every verified save produced the exact right server payload; replay, backoff, timer survival, and per-child state all pass), but the timeline does not reliably show what was just saved, groups local entries into duplicate day sections, and a server-rejected edit leaves an entry invisible and unresolvable. Together these break the core trust loop ("log it, see it land") that the spec makes the product's heart (spec §2/§3, UX §3: "the animation *is* the confirmation").

**Counts: P0 = 0 · P1 = 3 · P2 = 2 · P3 = 5.**

---

## P1 findings

### 1. Newly saved entries do not appear in the timeline until a restart (P1)
- **Repro:** fresh install → login → Feeding quick action → set formula/bottle/120 mL → Save (online, sync 201). Timeline unchanged 60+ s later. Repeat with two diaper saves online: neither row renders 2.5 min later.
- **Expected (spec §3, UX §3):** the entry appears at the top of Today with the insert animation immediately on save — "the animation *is* the confirmation".
- **Actual:** no row until the app is restarted (diapers, offline notes) or, in one case, until a pull-to-refresh (feeding). Data is safe (server rows verified via `api.sh`); it is purely a render/composition failure — deletes, by contrast, update the list in place instantly.
- **Evidence:** `docs/screenshots/new-app/20-quick-actions/04…08`, `13`; applog `panel save → dispatch CREATE … 201` with unchanged UI dumps.

### 2. Locally created entries render in a duplicate "Today" day-group (P1)
- **Repro:** after the rows do appear (post-restart), all session-created entries sit under their own "Today" header, with the server-fetched entries under a second "Today" header below; the split never merges, even after restart and refresh.
- **Expected (spec §2):** one timeline grouped by day, newest first.
- **Evidence:** `docs/screenshots/new-app/30-timeline-edit/06`, `08` (two "Today" headers, spatially verified row dumps); persists across restart.

### 3. Server-rejected edit is swallowed; entry becomes invisible and unresolvable (P1)
- **Repro:** (a) online: edit a sleep entry's start −15 m so it overlaps another sleep → Save → `PATCH` 400 (`BB/SYNC op #15 EDIT rejected: Another entry intersects the specified time period. Conflicting entry: Sleep (…)`). (b) offline variant reaches the same parked-op state.
- **Expected (spec §3.8, §5, §6.2, §6.3.2):** online → non-destructive "Can't save changes" dialog with a parent-language reason naming the clash, sheet stays open with values; offline/queued → FAILED badge on the row; tapping it opens the failure dialog with **Edit / Retry / Delete locally**; never auto-delete; never blocks the queue.
- **Actual:** no dialog of any kind; the sheet closes silently; the affected entry's row **disappears from the timeline** (persists across app restart and pull-to-refresh); no `badge_failed` renders on any row; the header chip counts "1 failed" but tapping it only triggers a drain ("Sync now") — the op stays parked and **no resolution path (Edit/Retry/Delete-locally) is reachable**. The parked op correctly does not block other entries' sync (5 subsequent ops replayed fine), so §6.2's queue-independence holds; the surfacing/resolution contract does not.
- **Collateral:** a second, fully-synced sleep entry (A2) also stopped rendering after the restart in the same session — timeline completeness degrades while a failed op is parked.
- **Evidence:** `docs/screenshots/new-app/40-offline-sync/02…07`; applog `23:07:06 op #15 EDIT rejected: …`; `api.sh GET /api/sleep/7/` (server entry unchanged, correct).

---

## P2 findings

### 4. Panel close (✕) does not run the stop guard on a running timer (P2)
- **Repro:** start the Feeding timer (>10 s), open its panel, tap the panel's ✕.
- **Expected (UX spec §3):** ✕ is a stop-guard trigger — >10 s dialog ("Stop timer?"), ≤10 s silent discard.
- **Actual:** panel closes silently; the timer keeps running (observed running 5+ min; strip + running dot still active).
- **Evidence:** `docs/screenshots/new-app/20-quick-actions/25-milo-timer-still-running.png`; applog `start FEEDING … localId=7` with no stop/stop-guard events before the next child switch.

### 5. CareDashboard nap-row tap launches the app despite the trampoline (P2)
- **Repro:** home screen, no timer running, tap the "Last nap" row (twice, cleanly: launcher verified resumed before each tap).
- **Expected (spec §7.2, widgets.md):** nap row starts the sleep timer without opening the app (QuickActions sleep cell proves the launcher can stay foreground — that path uses a broadcast).
- **Actual:** timer starts (`POST /api/timers/` 201) **and** `MainActivity` comes to the foreground (activity-based trampoline lands on the app task).
- **Evidence:** `docs/screenshots/new-app/50-widgets/07-nap-row-tap-result.png`; `dumpsys activity activities` before/after.

---

## P3 findings

### 6. Diaper headline carries a redundant color parenthetical (P3)
`Wet + Solid (Yellow) ● Yellow` / `Wet (Brown) ● Brown`. Spec §4.1 wants `Wet + Solid` plus the color swatch/name — the `(Color)` text duplicates the chip. Evidence: `20-quick-actions/06-state-check.png`, `13`.

### 7. Long-press → Configure is unavailable on placed widgets (P3)
Both `*widget_info.xml` declare `android:configure` but not `android:reconfigurable`; long-press on a placed widget goes straight to launcher resize mode with no Configure entry. Reconfiguration is only possible by removing and re-adding the widget (add-time config screens verified: `docs/screenshots/new-app/widgets/*_config.png`). Spec §7.3 and widgets.md say long-press → Configure. Evidence: `50-widgets/09-longpress-menu.png`; `app/src/main/res/xml/*widget_info.xml`.

### 8. Documented dialog/sheet testTags are absent from the accessibility tree (P3, automation contract)
`dashboard/stop_dialog*`, `dashboard/duplicate_dialog*`, and **all** `edit/*` tags (`edit/sheet`, `edit/stepper/*`, `edit/save`, `edit/delete`, `edit/validation`, `edit/failure_dialog`) are defined in `TestTags.kt` and promised in FEATURE-CHECKLISTS 4/5/7, but never appear in `uiautomator` dumps; while any dialog is up, the tags of the screen behind it disappear too (dialogs mask the tree). QA had to drive the stop dialog, duplicate dialog, and the entire Quick Edit sheet by text bounds — fragile and contrary to RUNBOOK rule 2 ("tap by testTag"). Evidence: session dumps (this pass); workaround scripts in flow docs 20/30.

### 9. About lacks the read-only "Connection" section (P3)
About shows version, description, and upstream licenses/links, but no server URL + username (spec §9 / UX spec §2 "About includes a read-only 'Connection' section"). Evidence: `60-help-about/05-about.png`.

### 10. Fresh-install login does not fetch `/api/timers/` (P3)
After a data wipe, login's request chain omits `GET /api/timers/` (verified in access log), so an orphan server timer (e.g. from a prior install) is invisible in-app until the next app start, which adopts it (`BB/TIMER restore SLEEP serverId=9`). Self-heals on restart; low impact, but the first run after a wipe can silently double-log sleep if the user starts a new timer while the orphan still runs server-side. Evidence: login-chain server log vs post-restart log.

---

## Skipped / not covered

- **Spec §6.3.6 (401/session expired):** no debug mechanism to revoke the session without touching the admin API key used by the QA scripts; skipped per mission instruction. Code path exists (LoginViewModel session prefill) but was not triggered black-box.
- **Spec §6.3.5 (two devices, near-duplicate diapers):** single emulator; accepted tradeoff.
- **Sign-out flow (checklist 8):** not in this pass's mission scope (A–F); overflow menu verified to list it.
- **Pagination of older pages:** seed data only reaches "Yesterday" in the loaded window; older-page loading not exercised.

## Environment notes (not app bugs)

- The emulator image ships **only voice-typing IMEs**; every text field raises a full-screen "Tap to speak" overlay that occludes lower UI. Worked around by dismissing the IME (`BACK` / its own back arrow) before tapping; several early session mis-taps were caused by this, not the app.
- `scripts/qa/ui-tap-tag.sh --dump-after` **taps** the tag as a side effect; read-only inspection must use raw `uiautomator dump`. Worth a note in RUNBOOK.

## What was left running / final demo state

- App logged in (Milo active), both widgets placed and live, ~15 fresh events across all six types on the server (feeding, diapers incl. wet+solid+color+amount, sleep, notes, pumping 12 min, tummy time), no timers running, server up, airplane mode off. The "1 failed" chip reflects the parked failed edit ops from finding #3 and was deliberately left as evidence.

---

## Resolution (Maintainer, 2026-10-17)

All P1 and P2 findings were fixed with regression tests and re-verified on the emulator (evidence: `docs/screenshots/new-app/70-fixes/`):

| Finding | Fix commit | Root cause (one line) |
|---|---|---|
| P1-1 timeline not live | `1004ab2` | Render window was a value-read inside the uiState combine with a circular older-load check — window wedged at 30 rows |
| P1-2 duplicate "Today" | `1004ab2` | Day groups computed in UTC-epoch-day while labels rendered device-local |
| P1-3 rejected edit swallowed | `1004ab2` | Frozen window hid the row + no proactive failure surfacing + chip only drained; now `onEntryFailed` → failure dialog, chip scrolls to failed row |
| P2-4 panel ✕ skipped stop guard | `5e2670d` | `onClosePanel` bypassed `requestStopGuard` |
| P2-5 nap row launched app | `8257abf` | Deep-linked through an activity instead of the broadcast path |
| P3-8 missing dialog/sheet tags | `87471ee` | Tags added |

**Updated verdict: shippable beta.** 0 P0 · 0 P1 · 0 P2 open. Remaining P3 items are cosmetic and tracked above.

---

## Regression gate — 2026-10-18

After the owner UI rework (v0.4, `90-owner-ui-rework.md`), persona round 2 (widget running-first row, info rows open the app — `widgets.md`), and the ">5-minute range" timeline tweak, `scripts/qa/capture-flows.sh --assert` ran **fully green: 4/4 canaries** — A1 save→row visibility, A2a native-picker edit, A2b in-place row update, overlap→FAILED→resolve. All flow PNGs under `docs/screenshots/new-app/flows/` were regenerated and committed in the same commits as the changes. The findings and verdicts above are unchanged.

---

## Finding (Maintainer QA, 2026-10-19): adopted-timer STARTED row showed "just now" — FIXED (P2, display-only)

**Found while** verifying the round-7 icons on-device: after an app relaunch with a
running timer, the auto-reopened card read "Started 19:58 · just now" on a
~50-minute-old timer (and kept claiming the current minute). The elapsed counter and
the save path were correct — `startedLabel` read the restored draft's relative
`startedMinutesAgo` (0 after restore) instead of the adopted timer's absolute start.

**Fix:** `startedLabel` now derives the age from the timer's `startedAtMillis` (plus
any Adjust backdate, mirroring `TimerRepository.stopAndSave`'s `start = timerStart −
adjust`, so the row and the saved start cannot disagree). Pinned by
`StartedLabelTest` (5 cases incl. adjust-vs-save consistency) and the new **A3
capture canary** (restart-mid-timer → STARTED row must read "…m ago", never
"just now").

**Same-day process find:** the 2026-10-19 icon gate rebuilt the APK but never
installed it, so a full PNG set was captured and committed against the previous
build; the gate now syncs + verifies the install (`sync_apk`, RUNBOOK rule 6). A
hand-saved demo entry overlapping the A2 edit window also cascaded A2b +
overlap-resolve failures (RUNBOOK rule 7).

---

## Final acceptance pass — 2026-10-19 (round-7 icons + startedLabel fix)

**6/6 PASS** (grid icons light+dark · timeline trio glyphs + range rows · adopted-timer
STARTED row after relaunch · long-press web escape hatch · both widgets · offline
PENDING→synced). Evidence: local QA session artifacts (not committed). New findings,
none blocking:

- **P2-11 new-note sheet restores stale draft text** — after a note save + sync, the
  NEXT new-note sheet opens pre-filled with the previous note's text; one accidental
  Save ships junk. Recommendation: clear the text draft on successful save (keep the
  process-death restore for *open* drafts, which is by design). Fix needs a JVM test +
  a capture assertion (03 note panel starts empty) per the regression rule.
- **P3-12 quick-actions widget "Bottle" last-line keys on any feeding method** — the
  cell shows the last FEEDING regardless of method under a "Bottle" label (reads 44m
  for a breastfeed). Arguably by design (cell label = the tap action, not the stat);
  if kept, consider relabeling the line "Fed 44m ago". UX call, owner to decide.
- **P3-13 `edit/delete_confirm` testTag covers the whole dialog** — center taps are a
  silent no-op; script deletes must target the "Delete" text node. Fix the tag bounds
  next time QA scripts are touched.

Observation (deliberate-decision backlog): the app pulls server data only on process
start — no pull on resume, and server-side deletions don't propagate to the local
timeline. Consistent with offline-first so far, but worth an explicit spec decision
(pull-to-refresh? tombstones?).

---

## Docs-site capture wave findings (2026-10-20)

**P1-14 (FIXED): cold-start widget stop deep link crashed the app** — NPE in
`awaitDashboardReady` (uiState read mid-construction; Main.immediate synchronous
collection). Fixed via `uiStateConstructed` gate; pinned by
`DashboardDeepLinkColdStartTest` (red/green verified). Found by the demo-widget
capture; also surfaced the ≤10s silent-discard branch consuming early taps (by design).

**P2-15 group-05 offline/serverdown capture steps env-flake in every full-gate run**
(3 runs, incl. one on a cold-booted emulator): after airplane-on + restart, the helper
reports "dashboard not visible" (final wait now bumped 10→20 s), the NOTE quick-action
tag then goes unfound, and the server-down pending entry + drain shots cascade.
Manual verification shows the app itself renders the dashboard correctly offline in
~8 s — the failure is in the step sequence, not the app. `05-sync-states-02-serverdown-pending.png`
is therefore intentionally ABSENT from docs/screenshots/new-app/flows/ as of 2026-10-20
(the new honest-failure rm replaced an Oct-17 stale file). Re-capture it once the step
sequence is fixed. A hand-capture attempt (2026-10-20, ~02:00) also failed silently —
the note sheet's Save closed without creating the entry — reinforcing that this step
needs a proper fix, not retries. `06-widgets-03-home-sleep-running.png` was captured
out-of-gate the same night (documented deviation) after three gate runs failed its
running-row poll; the state itself verified trivially by hand. Canaries in the same runs: 5/5 green on run 3 (cold boot).

**Also filed today:** P2-11 stale note-sheet draft, P3-12 widget "Bottle" last-line
keys on any feeding method, P3-13 `edit/delete_confirm` tag bounds. P2-11 did NOT
reproduce during the server-down hand-capture attempt (fresh sheet opened empty).
