---
title: "Feature checklists"
---

# Feature checklists — concrete verification steps

> **Flow screenshots:** the canonical PNG set under `docs/screenshots/new-app/flows/` can be regenerated with `scripts/qa/capture-flows.sh` (see RUNBOOK "Flow capture & regression canaries") instead of hand-capturing. Run it after any UI-affecting change; `--assert` adds the save-visibility/edit-update regression canaries.

Per `docs/qa/RUNBOOK.md` rule 7: every feature ships with an entry here in the
same change. Steps name scripts (`scripts/qa/*`), testTags (resource-ids; see
`app/app/src/main/java/app/dandle/ui/TestTags.kt`), and the expected
server requests (`scripts/qa/server-log.sh`, `scripts/qa/api.sh`). JVM tests are
run with `./gradlew :app:testDebugUnitTest` from `app/` (after
`source scripts/dev-env.sh`). A checklist row is not "done" until QA has driven
it on the emulator with screenshots archived under `docs/screenshots/`.

---

## 1. Fresh-install login (spec §1.1)

**Goal:** cleared app data -> login screen -> signed in -> dashboard.

**Steps**
1. `scripts/qa/fresh-install-login.sh` (defaults: `app.dandle.debug`, `http://10.0.2.2:8000`, `admin`/`admin`). It clears data, disables animations, and types the fields by tag — fresh dump before every tap.
2. To watch it live: `scripts/qa/applog.sh` in a side terminal; `scripts/qa/server-log.sh -f` for the request lines.
3. Script exits 0 only when tag `dashboard` is visible. `scripts/qa/shot.sh qa/login/05-dashboard-after-fresh-install.png` for evidence.

**Tags:** `login/server_url`, `login/username`, `login/password`, `login/submit`, text node `Continue` (cleartext warning), `login/error_banner` (failure), `dashboard`.

**Expected server requests** (via `server-log.sh`): `GET /login/` (csrf) -> `POST /login/` -> `GET /api/profile` -> `GET /api/children/`. No 401/4xx beyond the intended csrf dance.

**Expected UI states:** login fields empty after `pm clear`; http:// URL pops the unencrypted-connection warning (tap Continue); on success dashboard with child switcher + timeline. Wrong password -> `login/error_banner` with parent-language copy (script exits 2 and prints the banner text).

**JVM tests:** `LoginFlowTest`, `ServerUrlNormalizerTest` (scheme-less / trailing-slash URLs).

## 2. Feeding quick action (spec §3.1)

**Goal:** create a feeding from the dashboard quick action.

**Steps**
1. Login (checklist 1).
2. `scripts/qa/ui-tap-tag.sh dashboard/quick_action/FEEDING` — timer starts; running panel appears.
3. `scripts/qa/ui-tap-tag.sh dashboard/panel/amount` then type mL via `adb shell input text` (re-dump first if a dialog/keyboard shifted layout). Check default type/method remembered per child.
4. Save: `scripts/qa/ui-tap-tag.sh dashboard/panel/save`.
5. Verify row: `scripts/qa/ui-text.sh` on the new timeline row tag (`dashboard/timeline/<localUuid>`); verify server: `scripts/qa/api.sh GET /api/feedings/ | head -40`.

**Tags:** `dashboard/quick_action/FEEDING`, `dashboard/panel/FEEDING`, `dashboard/panel/amount`, `dashboard/panel/feeding_type/<wire>`, `dashboard/panel/feeding_method/<wire>`, `dashboard/panel/save`.

**Expected server requests:** on start: `POST /api/timers/` (named `Feeding-BBapp:<n>`); on save: `POST /api/feedings/` `{child, start, end, type, method, amount?}` + `DELETE /api/timers/<id>/`.

**Expected UI states:** running panel with live counter; after save the timeline headline follows §4.1 format; no PENDING badge when online.

**JVM tests:** `FeedingOptionsTest` (type->method cascade, solid food methods), `QuickActionStoreTest`, `DuplicateGuardTest` (§3.7: "logged N min ago. Add anyway?" — `dashboard/duplicate_dialog` + `dashboard/duplicate_dialog/add`).

## 3. Diaper quick action (spec §3.2)

**Goal:** create a diaper change (no timer).

**Steps**
1. Login (checklist 1).
2. `scripts/qa/ui-tap-tag.sh dashboard/quick_action/DIAPER` — panel opens, save disabled.
3. Toggle: `ui-tap-tag.sh dashboard/panel/wet` (save enables). Optional: `dashboard/panel/solid`, extra options `dashboard/panel/extra_options` -> color chips `dashboard/panel/color/<wire>`, `dashboard/panel/amount`.
4. Save: `ui-tap-tag.sh dashboard/panel/save`.
5. Verify: `scripts/qa/api.sh GET /api/changes/` — newest entry has correct wet/solid/color/amount.

**Tags:** `dashboard/quick_action/DIAPER`, `dashboard/panel/wet`, `dashboard/panel/solid`, `dashboard/panel/extra_options`, `dashboard/panel/color/<wire>`, `dashboard/panel/amount`, `dashboard/panel/save`.

**Expected server requests:** `POST /api/changes/` `{child, time, wet, solid, color?, amount?}` (endpoint is `/api/changes/`, not `/api/diaperchanges/`).

**Expected UI states:** save disabled until Wet or Solid on; timeline row appears immediately (local-first), no badge when online.

**JVM tests:** `QuickActionStoreTest`, `MappersTest` (change DTO round-trip).

## 4. Sleep timer start/stop/save (spec §3.3 + §3 common rules)

**Goal:** timer lifecycle: start, live elapsed, stop >10s -> confirm dialog, save.

**Steps**
1. Login (checklist 1).
2. Start: `ui-tap-tag.sh dashboard/quick_action/SLEEP`; the running card appears BELOW the grid (tag `dashboard/panel/SLEEP`) with a ticking `H:MM:SS` counter and an icon-only save (`dashboard/timer/SLEEP/save`). v0.4: no strip, no ✕.
3. Kill the app (`adb shell am kill app.dandle.debug`), relaunch via launcher — timer restored and still ticking (spec: survives process death).
4. Stop after >10s: `ui-tap-tag.sh dashboard/timer/SLEEP` -> stop dialog `dashboard/stop_dialog` with `dashboard/stop_dialog/save` (emphasized), `keep`, `discard`.
5. Save: `ui-tap-tag.sh dashboard/stop_dialog/save`; verify `scripts/qa/api.sh GET /api/sleep/`.
6. \<10s path: repeat quickly — stop must discard silently (no dialog).

**Tags:** `dashboard/quick_action/SLEEP` (re-tap = tap-to-stop), `dashboard/panel/SLEEP` (the card), `dashboard/timer/SLEEP/save`, `dashboard/stop_dialog/{save,keep,discard}`.

**Expected server requests:** start: `POST /api/timers/` (named `Sleep-BBapp:<n>`); save: `POST /api/sleep/` `{child, start, end, notes?}` + `DELETE /api/timers/<id>/` (nap flag is server-computed; app must not send it).

**Expected UI states:** card only while running, always below the (never-moving) buttons; elapsed H:MM:SS; re-tap >10s -> "Stop timer?" dialog with Save emphasized, ≤10s silent discard; timeline entry SYNCED and scrolled into view.

**JVM tests:** `StopGuardTest` (>10s vs \<10s), `TimerRepositoryTest` (restore after process death).

## 5. Quick edit time nudge (spec §5)

**Goal:** fix an entry's time through the standard native date/time pickers (v0.4: no steppers).

**Steps**
1. Login; ensure at least one entry exists (checklist 2 or 3).
2. Tap its timeline row (`dashboard/timeline/<localUuid>`) -> `edit/sheet` opens.
3. Move start: `ui-tap-tag.sh edit/sheet/time_start` → native TimePicker pre-populated → pick → OK (calendar button = DatePicker first). For timed types moving start shifts end by the same delta (duration preserved). Scripted: `pick_time` in `capture-flows.sh`.
4. Save: `ui-tap-tag.sh edit/save`; timeline row updates in place.
5. Verify: `scripts/qa/api.sh GET /api/<type>/<id>/` shows the picked time; `server-log.sh` shows the `PATCH`.

**Tags:** `edit/sheet`, `edit/sheet/time_{start,end,time}`, `edit/validation`, `edit/save`, `edit/delete` (all reachable in the dump since `dialogTestTags()`).

**Expected server requests:** `PATCH /api/{type}/{id}/` (never PUT — always 405 on Baby Buddy).

**Expected UI states:** sheet with type-appropriate fields; inline validation (end after start, no future times, \<=24h) disables save with a reason; no dialog on clean save.

**JVM tests:** `TimeEditLogicTest` (duration-preserving shift, constraints), `EventRepositoryEditTest`.

## 6. Offline create -> replay (spec §6.3.1)

**Goal:** entries created offline queue as PENDING, then replay in order when connectivity/server returns.

**Steps**
1. Login; baseline `scripts/qa/server-log.sh -n 5`.
2. Go offline: `scripts/qa/offline.sh on` (airplane mode) **or** `scripts/qa/server.sh stop` (server-down flavor — use one flavor per run, both must pass).
3. Create a note (`dashboard/quick_action/NOTE` -> `dashboard/panel/text` -> `dashboard/panel/save`) and a diaper (checklist 3). Timeline rows appear instantly; badge `dashboard/badge_pending` ("Will sync").
4. Kill + relaunch app offline: pending entries intact (spec §6.3.4).
5. Recover: `scripts/qa/offline.sh off` or `scripts/qa/server.sh wait`.
6. Watch replay: `scripts/qa/applog.sh` (BB/SYNC), `scripts/qa/server-log.sh -f` — creates POST in outbox order (oldest first); badges clear; `scripts/qa/api.sh GET /api/notes/` shows them.

**Tags:** `dashboard/quick_action/NOTE`, `dashboard/panel/text`, `dashboard/panel/save`, `dashboard/badge_pending`, `dashboard/sync_chip`.

**Expected server requests:** while offline: none (verify `server-log.sh -n 20` stays flat for the app's host). On recovery: queued `POST /api/notes/`, `POST /api/changes/` in creation order.

**Expected UI states:** PENDING badge while queued; badge disappears on sync; sync chip in header reflects draining.

**JVM tests:** `OutboxDrainerTest` (oldest-first, backoff, drain independence), `Iso8601Test` (wire times).

## 7. Server-rejected (overlap) FAILED flow (spec §6.3.2, §3.8)

**Goal:** an entry the server rejects (overlapping sleep) ends FAILED with a resolution path — never a dead end, never auto-deleted.

**Steps**
1. While server is down (`scripts/qa/server.sh stop`), create sleep entry A: `dashboard/quick_action/SLEEP` -> stop -> save (offline).
2. Create overlapping sleep entry B (times intersecting A) — also offline.
3. `scripts/qa/server.sh wait`; let sync drain: A syncs, B is rejected (`non_field_errors`: "Another entry intersects the specified time period").
4. B shows `dashboard/badge_failed` — v0.4: a small warning badge on the row's type icon (normal row height, no header chip); A must still sync (FAILED must not block the queue). `scripts/qa/api.sh GET /api/sleep/` — only A on server.
5. Tap FAILED badge -> failure dialog `edit/failure_dialog`: parent-language reason naming the clashing entry + **Edit** / **Delete locally** / **Retry** (`edit/retry`, `edit/delete`).
6. Resolution path A: Edit via sheet -> non-overlapping times -> save -> retries -> SYNCED. Path B: `edit/delete` -> row gone locally, nothing on server.

**Tags:** `dashboard/badge_failed`, `edit/failure_dialog`, `edit/sheet`, `edit/retry`, `edit/delete`, `edit/validation`.

**Expected server requests:** replayed `POST /api/sleep/` x2 — one 201, one 400 with `non_field_errors`; after edit-resolve: `PATCH`/re-POST 201.

**Expected UI states:** amber FAILED badge on B only; dialog copy translated to parent language (spec §3.8: "This overlaps the 1:15–2:00 PM nap"), values intact for edit.

**JVM tests:** `ConflictPolicyTest`, `FailureMessageTest` (server error -> parent-language copy), `OutboxDrainerTest` (failed op does not block the rest).

## 8. Sign-out (spec §1.2)

**Goal:** sign out clears stored key + local cache; data on server untouched.

**Steps**
1. Login (checklist 1); note an existing entry on the server via `scripts/qa/api.sh GET /api/children/`.
2. `ui-tap-tag.sh dashboard/overflow` -> tap "Sign out" -> confirmation dialog (`dashboard/overflow` menu; confirm tag per implementation — dump the tree with `ui-tap-tag.sh --dump-after`).
3. Confirm -> app returns to login screen (`login/server_url` visible; use `ui-text.sh` / dump to verify).
4. Relaunch app (no data clear): login screen again, not dashboard (stored key gone). Server still has all entries (`api.sh GET /api/children/` unchanged).

**Tags:** `dashboard/overflow`, confirmation dialog (dump to discover), `login/server_url` (post-condition).

**Expected server requests:** on next successful login the normal chain (checklist 1). No DELETEs to the API during sign-out.

**Expected UI states:** confirm dialog warns local data is cleared and server data untouched; after sign-out, back navigation must not re-enter the dashboard.

---

## 9. QuickActions widget placement + taps (spec §7.1, §7.3)

**Goal:** the 2×2 quick-actions widget places, configures, and drives in-app semantics from the home screen.

**Steps**
1. Fresh session: `scripts/qa/fresh-install-login.sh` (widget taps resolve the active child from the persisted selection).
2. Long-press launcher → Widgets → the "Quick actions" card previews the real 2×2 chip layout (not the loading grid) and is labeled **2 × 2** (UX-review W1/W7) → drag to the home screen → the configure screen opens (`QuickActionsConfigActivity`): pick the child and four buttons (default Sleep / Bottle / Diaper / Pump) → **Save**.
3. Tap the **Sleep** cell (idle) → NO app launch; a server timer appears (`scripts/qa/server-log.sh` shows `POST /api/timers/`), the cell turns accented with minute-granular elapsed, and the CareDashboard running row appears if placed.
4. Tap the **Sleep** cell (running; v0.4: lit in the sleep type color with the elapsed in place of the label; idle cells show a subdued last-activity line like `1h30m`) → the app opens straight into the stop-guard dialog (widgets cannot host it — see `docs/flows/new-app/widgets.md`).
5. Tap **Diaper** → the app opens with the Diaper panel pre-opened (wet/solid toggles). Same for Bottle (feeding timer + panel) and Pump.
6. Long-press widget → a **Configure** entry must exist (UX-review W10) → change a slot → Save → the cell updates in place.
7. Theme variant: switch system dark mode (`adb shell "cmd uimode night yes"`) and use a light wallpaper → the widget surface and chips follow the system theme; text/icons stay readable (UX-review W5). Verify both directions (`night no` / `night yes`).

**Tags:** launcher-driven (no in-app tags); verify via `adb shell uiautomator dump` on the home screen.

**Expected server requests:** `POST /api/timers/` on sleep start; `POST /api/feedings/` + timer DELETE after an in-app feeding save; nothing on pure navigation taps.

**Expected UI states:** widget paints a surface background (never wallpaper-show-through); idle slots = rounded surfaceVariant chips (24dp glyph + 12sp label); running slot = rounded primaryContainer chip + elapsed text ("12m") replacing the label (UX-review W3/W4); 4×1 resized variant = one content-capped centered chip row with labels (never stretched to the frame height, W2).

**JVM tests:** `QuickActionActionsTest` (facade semantics), `WidgetStateMapperTest` (slot accents + elapsed).

---

## 10. CareDashboard widget rows + refresh (spec §7.2)

**Goal:** the 4×2 care dashboard shows last feed/diaper/nap/pump (+ running row) and refreshes without opening the app.

**Steps**
1. With data on the server: the widget card previews the real layout (child name + rows + running chip, UX-review W7); place "Care dashboard" (long-press → Configure: child + row visibility) → child-name header, then rows `Last feed · 12m · 120 mL` shape, minute-granular (header per UX-review W8).
2. In the app: log a new feeding → the widget's feed row updates WITHOUT opening anything (in-app write refresh).
3. Stop the server (`docker stop babybuddy`), log two overlapping naps in-app → PENDING → start the server → after drain the widget reflects the synced rows (drain-completion refresh).
4. Nap row tap → OPENS the app (verify `MainActivity` resumed; no timer starts from the widget). Feed/diaper/pump row taps open the app with that panel pre-opened. Running row tap opens the plain app.
5. Wait 15+ minutes (or `adb shell am broadcast -a android.intent.action.TIME_SET` to nudge the scheduler in QA) → periodic refresh keeps "Xm ago" honest.
6. Theme variant: `adb shell "cmd uimode night yes"` on a light wallpaper (and the reverse) → the widget surface and the running-row accent follow the system theme; rows stay readable (UX-review W5).
7. Amount ≤ 0 (e.g. the known failed-sync pump) shows only the since text — never "0 mL" (UX-review W9; same in the app timeline).

**Tags:** launcher-driven; in-app steps use standard dashboard tags.

**Expected server requests:** normal sync chain (checklist 1); no writes from widget taps — taps only open the app.

**Expected UI states:** rows with icon + label + `Xm · detail`; running row = FIRST row, rounded type-colored chip with horizontal padding, "Asleep since HH:MM · Xm elapsed" wording in onPrimaryContainer (UX-review W6; persona round 2); empty data → the row is omitted (never a blank stub).

**JVM tests:** `WidgetStateMapperTest` (rows, visibility, cross-child isolation, since/duration edges incl. future skew and days).


---

## TEMPLATE (copy for every new feature)

## N. `<Feature name>` (spec §`<ref>`)

**Goal:** `<one line: what user outcome is proven>`.

**Steps**
1. `scripts/qa/<script>.sh` `<args>` — `<what it proves>`.
2. `scripts/qa/ui-tap-tag.sh <tag>` — `<action>`.
3. Verify server: `scripts/qa/api.sh <METHOD> <path>` — `<expected>`.

**Tags:** `<tag/a>`, `<tag/b>`.

**Expected server requests:** `<METHOD> <path>` `<body/shape>`.

**Expected UI states:** `<before / during / after, incl. badges & dialogs>`.

**JVM tests:** `<TestClass>` (\<what it pins>).
