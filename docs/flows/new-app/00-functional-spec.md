---
title: "Functional spec"
---

# Dandle — Canonical Functional Specification (v0.4 BETA)

> **This document is the product of record.** Any implementation (Kotlin/Compose, Flutter, React Native, or a future rewrite) must satisfy every MUST here. Screenshots referenced under `docs/screenshots/` are normative evidence. Parity baselines: the existing Baby Buddy for Android v2.6.4 flows mapped in `docs/flows/existing-app/`, and server behavior in `docs/research/babybuddy-api.md`.
>
> v0.2 changes (from user-persona review, docs/flows/new-app/02-user-persona-review.md): learning feeding defaults, 15-minute steppers + duration-preserving start moves, duplicate guard, parent-language failure messages, session-expiry reassurance, configurable widget buttons, Save-emphasized stop dialog.
>
> v0.3 changes (from persona beta round 1, docs/flows/new-app/80-persona-feedback/00-triage.md): panel "Started X min ago" adjust (§3), visible offline badge + "All synced" settle (§6.2), widget child-name prominence + cross-child timer line + stop-guard deep link (§7), login dead-button reason + neutral cleartext copy + paste-API-key path (§1.1).
>
> v0.4 changes (owner UI rework, docs/flows/new-app/90-owner-ui-rework.md): quick-action buttons pinned under the header with NOTHING above them; running timers render as the original app's "expanded top element" card below the buttons with an icon-only save and no ✕ (re-tap = stop); diaper/note creation moves to a sheet with a time row; Quick Edit time rows are native date/time pickers (steppers removed); failed entries mark the row's icon (no header chip); notes render italic; header shows the child's name + chevron (no app title); semantic per-type colors everywhere a type appears; widget tiles whisper the last activity and light up in their type color when running.
>
> v0.5 changes (design framework v2.3, source of truth: docs/design/candidates/dandle-ux-v3/mocks.html): running timers render as THEMED SCENE cards — one motif per activity (night/milk/sun/bottle), §8.1; the >10s stop guard becomes an ON-CARD stop band (no modal dialog, §3); feeding choices cascade (type gates methods) and a complete choice collapses to a summary (§3.1); the diaper sheet drops the TIME row (entries stamp at save time; backdate via edit) and picks color on a single-select slider (§3.2); amounts are unit-agnostic (label "Amount", never mL); the happy path stays silent — only PENDING/FAILED entries get indicators; ambient scene motion honors the system reduce-motion setting.
>
> Status markers: **[P]** parity with existing app (must match), **[N]** new in this app.

## 1. Identity & connection

### 1.1 First launch → login **[P]**
1. App opens to Login screen: fields **Server URL**, **Username**, **Password**; primary button **Sign in**. While the button is disabled (fields missing) it states WHAT is missing in place ("Enter the server URL, username, and password") — never a silent grey button. **[v0.3]**
1a. **Advanced: paste an API key** **[N, v0.3]**: an expander swaps the username/password fields for a single **API key** field. The key is validated exactly like the password path's final step (`GET /api/children/` with `Authorization: Token <key>`; a best-effort `/api/profile` read supplies the display name) and persists the same session. The key is stored app-private and the app opts out of Android backups (allowBackup=false) — the key to the household's data never leaves the phone via the backup door.
2. MUST accept URL with or without scheme (bare host gets `https://`). MUST trim trailing `/`.
3. If scheme is `http://`: confirmation dialog — "This server uses http:// — data is not encrypted on the way. Continue?" → Continue / Cancel. **[P]** (original warns too; v0.3 copy is neutral-informational — plain http is common on home networks, and the wording must not read as an accusation)
4. Sign-in sequence (server semantics per research doc §auth): GET `/login/` (csrf) → POST `/login/` → GET `/api/profile` → store `api_key` → validate with `GET /api/children/`.
5. Error states (distinct copy, no stack traces):
   - Unreachable server ("Couldn't reach the server — check the URL and your connection.")
   - 401/bad credentials ("Wrong username or password.")
   - 200 but zero children ("No children found. Create one in the Baby Buddy web app, then retry.")
6. On success → Dashboard. Credentials/apiKey persisted; subsequent launches skip login until Sign out or auth failure (401 → return to Login with "Session expired" notice).

### 1.2 Sign out **[P]**
Menu → Sign out: clears stored key and local cache. Confirmation dialog (data on server is untouched).

## 2. Dashboard (single screen; no tabs in beta) **[P]**

Layout, top to bottom (v0.4 — the grid NEVER moves; nothing dynamic renders above it):
1. **Header bar**: the active child's FIRST NAME as the title, with a chevron when more than one child exists (tap → dropdown of full names; default = first child; MUST persist selection across restarts) **[P: original-app placement]**; the app has no name in the chrome. Inline sync chip (only when it has something to say: Offline / Syncing… / All synced ✓ — never a failed count, §6.2) + overflow menu (Sign out, Help, About). One row.
2. **Quick actions grid**: exactly six buttons, this order: **Feeding, Diaper, Sleep, Notes, Pumping, Tummy time**. Icons are this app's own original artwork; a running or open type lights up in its semantic type color (§8) with a static running dot. **[P]**
3. **Running-timer card** (only while a timer runs for this child; at most one card — one editor at a time, original-app semantics): rendered DIRECTLY BELOW the grid as the original app's "expanded top element of the list" — literally the timeline's first list item, so it scrolls with the timeline and a field being typed into is brought into view above the keyboard (the header and grid never move); the list reveals the card the moment a timer starts. **[v0.5] The card is a themed SCENE card** (§8.1): a subdued per-activity background — night gradient with moon + twinkling stars (Sleep), warm milk gradient with floating drops (Pumping), sun gradient (Tummy time), bottle-blue gradient (Feeding) — under the fixed anatomy: icon chip · type label · live `HH:MM:SS` counter · the type's fields at full width (Feeding: cascade + amount + note, §3.1; Sleep: note; Pumping: amount + note; Tummy time: milestone + note), the compact "Started Xm ago · Adjust" line (§3), and an **icon-only check save** in the header. **No ✕, no "Save"/"Running" words, no synced indicator** (the happy path is silent, §8.1 rule 7) — stopping is the type button's re-tap (§3 tap-to-stop). **[P look, N fields]**
4. **Timeline**: most recent events (all six types), newest first, grouped by day ("Today", "Yesterday", date). Entry rows show a type-colored icon chip, headline (per type, §4.1), time-of-day (or a start–end range — see next bullet), duration (for timed types), sync-state marking (§6.2). Pull-to-refresh MUST re-sync. Pagination: load older on scroll (server limit/offset). After a quick-action save the timeline scrolls so the new row is in view — the insert IS the confirmation.
   - **Time formats (persona round 2, threshold widened per owner)**: sub-minute durations render `<1m` (never bare seconds); hours render without zero-pad (`1h 5m`, `4h`); ANY timed entry longer than **5 minutes** (or crossing local midnight) shows its RANGE as the trailing time (`21:38–22:00`) with the duration on the second line — no mental math for naps, feeds, pumps or tummy time, not just overnight sleeps.
   - The quick-action circle of a RUNNING type shows live status as its label (`Sleep · 12m`); the running card's save (icon-only check) sits in the card HEADER, always on screen.

Diaper and Note creation open a **modal sheet** (§3.2/§3.4), the same UI family as Quick Edit; they never render inline.

Empty state (fresh server): friendly illustration + "Log your first event with a button above."

## 3. Quick actions (create flows) **[P] — behavior must match existing app**

Common rules:
- Tap toggles: for the four timer types (Feeding, Sleep, Pumping, Tummy time), tapping the button starts a timer and shows its card below the grid (§2.3); **tapping the same button again is tap-to-stop** — it runs the stop guard below (>10s dialog, ≤10s silent discard). Never a duplicate timer. **[P: exact original tap semantics, v0.4]**
- The card's only action is the icon-only save (check). There is no discard/✕ on the card: discard = re-tap → Discard on the card's stop band.
- Timer >10s stop → **on-card stop band** (v0.5 — no modal): a band on the running card itself, "Stop timer?" + the spelled duration ("2 min 52 sec", never bare "0:16"), where **Save** is a full-width filled button; below it, "Keep running" (left) and **Discard** (right, subdued) sit on a SECOND row — the destructive action must not sit adjacent to Save (thumb-slip = erased nap; persona round 2, Jordan). \<10s → discard silently (the band never appears). **[P: original semantics, v0.5 on-card]**
- Timers MUST survive app restart and process death (restore countdown on launch). **[P]**
- Save = create entry with start = timer start, end = stop time. **[P]**
- **"Started X min ago" adjust (timed panels) [N, v0.3]**: the four timer panels carry one compact line — "Started just now" / "Started 20m ago" — with an **Adjust** expander offering **−15m / −5m / +5m / +15m** nudges and a **Pick time** platform time picker. The adjust backdates the ENTRY START only (start = timer start − adjust, clamped to the stop time); end and duration rules are unchanged. Clamp 0..24h; a future start is unreachable by construction. The adjustment persists with the panel draft and applies to both the panel Save and the stop-dialog Save.

### 3.1 Feeding **[P]**
- Start → running card: live counter, **two-state cascade** (v0.5, framework rule 5): initially only **Type** chips show (`breast milk, formula, fortified breast milk, solid food` — display "Fortified milk"); picking a type reveals **Method** chips FILTERED via `FeedingOptions.methodsFor` (breast milk → all six: bottle, left breast, right breast, both breasts, parent fed, self fed; formula/fortified/solid → bottle, parent fed, self fed — **solid food offers bottle**, verified in docs/flows/existing-app/04-feeding.md). When BOTH are picked, the rows COLLAPSE to a one-line summary ("Formula · Bottle ✎") — tapping the summary re-opens them — and only **Amount** + **Note** remain. Amount is unit-agnostic: the label is just "Amount", never mL (rule 10). The same cascade applies in the Quick Edit sheet. Defaults MUST remember the last-used type+method **per child** (falls back to breast milk + bottle on first use) — so with a learned default the card opens already collapsed.
- Save → `POST /api/feedings/` `{child, start, end, type, method, amount?, notes?}`. Amount optional for breast methods.
- Server timer (created on start, named `Feeding-BBapp:<n>`) DELETEd on save/discard. **[P]**

### 3.2 Diaper change **[P]**
- Opens a **modal sheet** (v0.4 — creation is an edit-like action, same UI family as §5). **No TIME row** (v0.5, framework rule: time = the save time; backdating happens through the edit flow — follow-up issue docs/issues-pending/gitea-issue-backdating.md).
- Toggles **Wet** and **Solid** (both allowed). **Save disabled until at least one is on** (a one-line hint says so).
- **Color** is a **single-select slider** (v0.5): one swipeable strip of swatch dots covering the full server enum (`black, brown, gray, green, orange, red, white, yellow` — research doc §4; `color` is ONE string, never multiple). The selected dot gets a ring + scale; every dot stays tappable (accessibility parity with the old chips; same per-wire test tags).
- **Amount** (numeric, optional) and **Note** (optional).
- Save → `POST /api/changes/` `{child, time, wet, solid, color?, amount?, notes?}`. time = save time.

### 3.3 Sleep **[P]**
- Start/stop timer; note field in running panel. Save → `POST /api/sleep/` `{child, start, end, notes?}`. Nap flag auto by server rules (don't send).

### 3.4 Notes **[P]**
- Opens a **modal sheet** (v0.4; **no TIME row** as of v0.5 — time = save time, backdate via edit): note text; Save (disabled when empty) → `POST /api/notes/` `{child, note, time}`.

### 3.5 Pumping **[P]**
- Start/stop timer; Amount (unit-agnostic label, v0.5) + note fields. Save → `POST /api/pumping/` `{child, start, end, amount?, notes?}`.

### 3.6 Tummy time **[P]**
- Start/stop timer; milestone text + note. Save → `POST /api/tummy-times/` `{child, start, end, milestone?, notes?}`.

### 3.7 Duplicate guard at save **[N]**
Before saving, if the same type already has an entry for this child within the last **4 minutes** (server data or local), show one calm line: e.g. "Wet diaper logged 4 min ago. Add anyway?" — **Add is the default action**; "View last entry" secondary. Never blocks; never silent-dedupes.

### 3.8 Conflict on save (overlap) **[P→N bridge]**
Server may reject with `non_field_errors` (overlapping Sleep/Feeding/Pumping/TummyTime; future times; start≥end — research doc §conflicts). Online: show a **translated, parent-language reason** (never raw server-speak; overlap errors MUST name the clashing entry, e.g. "This overlaps the 1:15–2:00 PM nap" — parse the conflicting-entry id from the server message when possible and link to it) in a non-destructive dialog; entry form stays open with values intact. Offline: entry goes to FAILED state (§6.3).

## 4. Timeline entries

### 4.1 Headline formats (MUST be consistent app-wide)
- Feeding: "Bottle · 120 mL" (method · amount) + duration. Non-breast-milk types are appended
  ("Bottle · Formula · 110 mL", "Parent fed · Solid food") — the method alone hides formula
  intake and erases solid food (persona round 2, Elena).
- Diaper: "Wet" / "Solid" / "Wet + Solid". The color is rendered ONCE per row (swatch dot +
  color name next to the headline) — never repeated inside the headline text.
- Sleep: "Sleep" + duration
- Note: first line of note text, rendered **italic** (v0.4): note content is words, not a structured event — italics keep "Feeding interrupted" the note from reading as a feeding. Note previews on other types' rows are italic too.
- Pumping: "Pump · 85 mL" + duration
- Tummy time: "Tummy time" + duration (+" · milestone" if set)

### 4.2 Interactions **[N — the core new feature]**
- **Tap** → Quick Edit sheet (§5). (Original app has no tap action; this is deliberate.)
- **Long-press** → opens the SAME event on the Baby Buddy web UI: `{serverUrl}/{uiPath}/{serverId}/` (uiPaths: `feedings`, `changes`, `sleep`, `notes`, `pumping`, `tummy-time`). MUST keep this mechanic at all times — the web app is the superset UI. **[P]**
  - Entry without serverId (offline-created, unsynced) → long-press is inert; badge explains why (§6).
- **Delete**: in the Quick Edit sheet (destructive, confirm dialog, then DELETE). Local-only failed entries delete only locally. **[P parity: original deletes via web; we add in-app delete as part of conflict resolution]**

## 5. Quick Edit sheet (Feature 1a) **[N]**

Material 3 bottom sheet, type-appropriate fields. Design goals: fix a time in ≤3 interactions; never more fields than the quick action itself.

- **Time editing** (all types, v0.4): start time (and end time for timed types) each get a row showing the stored value ("Today 14:32"); tapping it opens the **native time picker pre-populated with that value**; a small calendar button opens the **native date picker** (then the time picker). That is the whole affordance — no steppers (owner: the standard mobile date/time interface, as the Baby Buddy web UI does). **Moving the start time of a timed entry keeps its duration** (end shifts by the same delta); changing end adjusts duration independently. Constraint feedback inline: end must be after start; no future times; timed entries ≤24h (server rules). Save disabled with reason while invalid.
- **Type fields** mirror §3 exactly (feeding type/method/amount; diaper wet/solid/color/amount; sleep notes; note text; pumping amount/notes; tummy milestone/notes).
- **Save** → PATCH `/api/{type}/{id}/` (online) or queued edit op (offline §6). On success the timeline row updates in place (animated).
- **Conflict on edit** (overlap created by the new times) → same handling as §3.7.

## 6. Offline & sync (Feature 1b) **[N]**

### 6.1 Model
- Room/SQLite-equivalent local DB is the UI source of truth. Every write (create/edit/delete) records an outbox op and applies locally immediately.
- `SyncWorker` drains the outbox oldest-first when connectivity exists; exponential backoff; runs on app open, connectivity regain, and write.
- Local IDs (uuid) + nullable serverId; serverId filled on successful create.
- **Running timers reconcile both ways** [N, v0.4]: refresh adopts this child's server `-BBapp:` timers not tracked locally (process death, started on another device), and drops a local timer whose SERVER id no longer exists (it was stopped elsewhere — it must not keep "running" here forever). Offline-created timers (no server id) are never dropped: they are still waiting to sync.
- **Multi-device semantics [N, v0.4]**:
  - **Server entry deleted before a queued edit replays** → the replayed PATCH gets a 404; the op is resolved locally (dropped from the outbox) and the entry REMAINS on this device, flagged FAILED with the reason "This entry was deleted on the server." — resolvable through the §6.2 dialog (edit / delete locally / retry). Queued data never silently vanishes. (Counterpart: a 404 on a queued DELETE is treated as SUCCESS — the entry is already gone server-side, likely deleted elsewhere — and the local row is removed too.)
  - **Concurrent edits from two devices are last-write-wins at the ENTRY level.** A queued edit stores the FULL entry body at queue time (outbox payload = `{"serverId", body}` write DTO — never a per-field diff) and replays it as a PATCH. There is no field-level merge and no server-side versioning: whichever device's write reaches the server last replaces the whole entry, so a stale device can overwrite a newer edit made elsewhere. Accepted tradeoff — merge machinery is disproportionate for entries this small and this rarely co-edited; the Baby Buddy web UI remains the place to reconcile a surprising overwrite.

### 6.2 Sync-state badge per entry (timeline + sheets)
- **SYNCED**: no badge (clean look).
- **PENDING**: tonal pill — clock icon + "Will sync" in a secondary-container chip — shown while offline or queued. MUST be legible at arm's length: an offline-created row wears the badge from the moment it renders (no restart, no refresh required) [v0.3 visibility rule].
- **FAILED** (v0.4): a small error-colored **warning badge on the corner of the row's type icon** — the row keeps its normal height (no stacked "Sync failed" pill) and failure is surfaced ONLY on the row, never duplicated as a header chip. Tapping the row opens the failure dialog: parent-language reason (e.g. the overlap names the clashing entry), buttons: **Edit** (opens Quick Edit) / **Delete locally** / **Retry**. MUST NOT auto-delete. MUST NOT block other entries' sync.
- **"All synced" settle [N, v0.3]**: when a drain completes while online, the header chip shows **"All synced ✓"** for ~4 seconds (suppressed while offline — a failed drain synced nothing — and outranked by the Syncing state) — the visible "caught up" moment after queued entries land. The chip's clean default stays hidden.

### 6.3 Scenarios (MUST all pass QA)
1. Server down → create events → badge PENDING → server returns → events replayed in order → badges clear.
2. Server down → overlapping sleep entries created → replay: second rejected → FAILED badge + dialog flow (edit to non-overlap → retries → SYNCED; or delete locally).
3. Airplane mode mid-session → timer started offline keeps ticking → stop → entry PENDING → reconnect → syncs.
4. Kill app offline → relaunch → pending entries and running timers intact.
5. Two devices same child → both create near-duplicate diaper changes → both sync (server allows; no dedup) → visible in timeline (accepted tradeoff per product decision).
6. 401 during sync (key revoked) → pause queue, surface "Session expired — your N unsaved entries are safe on this phone. Sign in and they'll go up." → login → resume automatically.

## 7. Widgets (Feature 2) **[N]**

### 7.1 Quick Actions widget (2×2 or 2×1)
- Buttons: **four configurable slots** (default: Sleep toggle, Bottle, Diaper, Pump) — user picks any quick action per slot in the widget config (§7.3). Tap = same create/toggle semantics as in-app. **One-tap starts [N, v0.5]:** the timer types that need no field choices — Sleep, Pumping, Tummy time — start via a background broadcast (`WidgetTimerStartAction`): one tap starts the timer and the launcher never leaves the foreground. Types that need choices — Bottle (type/method), Diaper (wet/solid), Note — open the app with that panel pre-opened.
- Active-timer state: a RUNNING slot lights up in its **semantic type color** (not generic accent) and shows the minute-granular elapsed in place of its label. Tap on a RUNNING slot → opens the app **directly into the stop-guard dialog for that timer** (≤10s discards silently, in-app semantics; widgets cannot host the dialog themselves) [v0.3].
- **Last-activity line [N, v0.4]**: every idle slot whispers when its type last happened, in a subdued second line under the label — compact "45m", "1h30m", "2d" (since the newest event of that type for the widget's child; nothing when there is no history). Space-efficient: no "Last:" prefix.

### 7.2 Care Dashboard widget (4×2 or resizable 4×3)
- **Child-name header [v0.3]**: bold, full-contrast — the widget's WHO must be impossible to miss (the widget answers for one specific child).
- Rows (configurable set, default all): **Last feed** (X min ago + amount; feeding rows carry a method-specific icon — left / right / both / bottle), **Last diaper** (X min ago + wet/solid), **Last nap** (X h ago + duration), **Last pump** (X min ago + amount), plus **one Running-timer row PER running timer** rendered as the widget's FIRST rows, longest-running first — sleep, pumping, and tummy time can run simultaneously and each gets its own "Asleep since 13:00 · 12m elapsed" row [N, v0.5: was a single row that hid simultaneous timers].
- Tap a row → ALL info rows open the app (feed/diaper rows with that quick-action panel open; timers are never started from the dashboard widget). A running-timer row → opens the app **directly into the stop-guard dialog for that timer** (≤10s discards silently) [v0.3; persona round 2 — info rows opening the app is the specified behavior].
- **Cross-child running lines [N, v0.3]**: when timers run for DIFFERENT children, the widget shows one quiet, unaccented line per other-child timer, longest-running first — "[FirstName]'s [nap|feed|diaper change|pump] · Xm" — that opens the app (that timer's stop guard lives on that child's screen). No lines when no other-child timer runs; unknown child name reads "Another child" rather than guessing. Data isolation is unchanged — these lines are awareness, not access.
- Data: reads the local DB snapshot only (never the network at render time; sync happens in the background and refreshes the widgets on drain completion). Refresh on EVERY app write (timer start/stop/save/discard, entry create/edit/delete, child switch, sign-out) + on sync completion + a **~60s live tick while any timer runs** [N, v0.5: elapsed counters stay within a minute of the truth; the tick self-cancels when no timer runs — battery-conscious, see the WorkManager notes in `WidgetTickerWorker`] + WorkManager periodic (15 min) as the safety net. Counters are minute-granular ("Xm ago"); staleness below a minute is acceptable and documented (no per-second widget ticking).

### 7.3 Widget configuration
- One-tap add uses active child. Long-press → Configure: child selection, (dashboard widget) row visibility, and (quick-actions widget) the four button slots.

## 8. Look & feel (Feature 3) **[N]**

- Material 3, dynamic color from wallpaper on Android 12+, tasteful static palette fallback (self-hosted apps shouldn't scream brand).
- Dark theme follows system.
- Motion budget: animated timer counter, sheet slide-up, timeline item insert/remove, sync-badge transitions. Nothing decorative that delays logging.
- Type scale favors glanceability: durations and "time since" values are prominent.
- Original icon set (our own vector art; no copied assets from the original app — its media is attribution-licensed).
- **Semantic type colors [N, v0.4]** (Brief 03): each of the six types has a fixed hue family matching the original app's tints (feeding rose, diaper amber, sleep indigo, pumping magenta, tummy green, notes teal) expressed as light/dark container + on-container pairs (≥4.5:1 text contrast, pinned by test). They do NOT follow dynamic color — they are information. Used wherever a type appears: timeline icon chips, the running card's tile and save, the grid's running highlight, widget running accents.

### 8.1 Design framework v2.3 **[N, v0.5]**

The ten binding rules of the approved redesign (source of truth: `docs/design/candidates/dandle-ux-v3/mocks.html`). Where an older section contradicts them, THIS subsection wins.

1. **Entity hierarchy flows top-down** — entities render above their dependents; static identity chrome (child name, sync) may sit above the action grid, dynamic elements may not.
2. **The six actions are pinned instruments** — fixed position, never move, nothing dynamic above them.
3. **Card = live entity that can contain inputs** — stopping happens on the card; \<10s silently discards.
4. **Running timers render as themed scene cards** — one motif per activity (night/milk/sun/bottle); scenes are subdued backgrounds, never content; accessibility contrast holds light+dark (every text/surface pair ≥4.5:1, pinned by TimerSceneTest; scene palettes are self-contained so the card reads identically in both themes).
5. **Feeding choices cascade** (type gates methods per `FeedingOptions.methodsFor`) **and collapse to a summary when complete** — only Amount + Note remain visible.
6. **Timers count up as clocks** — `HH:MM:SS` with colons, never letters.
7. **The happy path is silent** — only PENDING ("Will sync" pill) and FAILED (icon warning badge) entries get indicators; SYNCED rows and running cards render no sync marking at all.
8. **History = the record pane** with infinite scroll (paged older-load; the card is its first item).
9. **Motion is ambient, out of the thumb path, honors reduce-motion** — scene decoration (star twinkle, drop float) reads the system remove-animations setting and renders static when it is on; nothing animated ever sits under an active thumb target.
10. **Amounts are unit-agnostic** — the label is "Amount", never mL; the wire payload units are the server's business (research doc §4), not the UI's.

## 9. Settings & help **[P/N]**
- Overflow menu: Sign out **[P]**, Help (short slideshow re-using original app's UX-guidance intent, our own content) **[P]**, About (version, licenses, links to both upstream projects) **[P]**.
- Server/account info display (URL + username, read-only in beta).

## 10. Non-functional MUSTs
- Cold start to dashboard ≤2s on Pixel 5-class hardware.
- Timer elapsed accurate across backgrounding (wall-clock based, not tick-count).
- All server field names/enum values exactly per `docs/research/babybuddy-api.md`.
- No crash on: no children, no network, invalid server cert, 500s, malformed data.
- Unit-test the sync engine, mappers, timer logic (framework per implementation).
