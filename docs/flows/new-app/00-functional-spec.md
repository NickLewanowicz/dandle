# babybuddy-next — Canonical Functional Specification (v0.2 BETA)

> **This document is the product of record.** Any implementation (Kotlin/Compose, Flutter, React Native, or a future rewrite) must satisfy every MUST here. Screenshots referenced under `docs/screenshots/` are normative evidence. Parity baselines: the existing Baby Buddy for Android v2.6.4 flows mapped in `docs/flows/existing-app/`, and server behavior in `docs/research/babybuddy-api.md`.
>
> v0.2 changes (from user-persona review, docs/flows/new-app/02-user-persona-review.md): learning feeding defaults, 15-minute steppers + duration-preserving start moves, duplicate guard, parent-language failure messages, session-expiry reassurance, configurable widget buttons, Save-emphasized stop dialog.
>
> Status markers: **[P]** parity with existing app (must match), **[N]** new in this app.

## 1. Identity & connection

### 1.1 First launch → login **[P]**
1. App opens to Login screen: fields **Server URL**, **Username**, **Password**; primary button **Sign in**.
2. MUST accept URL with or without scheme (bare host gets `https://`). MUST trim trailing `/`.
3. If scheme is `http://`: warning dialog — "Unencrypted connection. Your credentials and data travel in clear text. Continue?" → Continue / Cancel. **[P]** (original warns too)
4. Sign-in sequence (server semantics per research doc §auth): GET `/login/` (csrf) → POST `/login/` → GET `/api/profile` → store `api_key` → validate with `GET /api/children/`.
5. Error states (distinct copy, no stack traces):
   - Unreachable server ("Couldn't reach the server — check the URL and your connection.")
   - 401/bad credentials ("Wrong username or password.")
   - 200 but zero children ("No children found. Create one in the Baby Buddy web app, then retry.")
6. On success → Dashboard. Credentials/apiKey persisted; subsequent launches skip login until Sign out or auth failure (401 → return to Login with "Session expired" notice).

### 1.2 Sign out **[P]**
Menu → Sign out: clears stored key and local cache. Confirmation dialog (data on server is untouched).

## 2. Dashboard (single screen; no tabs in beta) **[P]**

Layout, top to bottom:
1. **Header bar**: app name + current child selector (chip/dropdown listing all children; default = first child; MUST persist selection across restarts) **[P]** + overflow menu (Sign out, Help, About).
2. **Active timers strip** (only when a timer is running): one card per running timer — type icon, live elapsed counter (HH:MM:ss), Stop/Save and Discard affordances. **[P]**
3. **Quick actions grid**: exactly six buttons, this order: **Feeding, Diaper, Sleep, Notes, Pumping, Tummy time**. Icons are this app's own original artwork. **[P]**
4. **Timeline**: most recent events (all six types), newest first, grouped by day ("Today", "Yesterday", date). Entry rows show type icon, headline (per type, §4.1), time-of-day, duration (for timed types), sync-state badge (§6). Pull-to-refresh MUST re-sync. Pagination: load older on scroll (server limit/offset).

Empty state (fresh server): friendly illustration + "Log your first event with a button above."

## 3. Quick actions (create flows) **[P] — behavior must match existing app**

Common rules:
- Tap toggles: for the four timer types (Feeding, Sleep, Pumping, Tummy time), tapping the button starts a timer; tapping again (or Stop) completes it. **[P]**
- If a timer for that type is already running for the active child, tapping the button focuses/opens that running timer instead of starting a duplicate. **[P]**
- Timer >10s stop → confirmation dialog ("Stop timer?") where **Save is the emphasized (filled) action**; Keep timer and Discard are subdued text buttons. <10s → discard silently. **[P]** (original semantics, emphasis per user review)
- Timer >10s stop → confirmation dialog ("Stop timer? Keep / Discard / Save"). <10s → discard silently. **[P]** (exact original semantics per existing-app flow docs)
- Timers MUST survive app restart and process death (restore countdown on launch). **[P]**
- Save = create entry with start = timer start, end = stop time. **[P]**

### 3.1 Feeding **[P]**
- Start → running panel: live counter, **Amount** numeric field (mL), **Type → Method** cascading selector with exact server enum values: type ∈ {breast milk, formula, fortified breast milk, solid food}; method ∈ {bottle, left breast, right breast, both breasts, parent fed, self fed} — **solid food offers bottle, parent fed, self fed** (verified in the existing app: docs/flows/existing-app/04-feeding.md). Defaults MUST remember the last-used type+method **per child** (falls back to breast milk + bottle on first use).
- Save → `POST /api/feedings/` {child, start, end, type, method, amount?}. Amount optional for breast methods.
- Server timer (created on start, named `Feeding-BBapp:<n>`) DELETEd on save/discard. **[P]**

### 3.2 Diaper change **[P]**
- Opens panel (no timer): toggles **Wet** and **Solid** (both allowed). **Save disabled until at least one is on.**
- "Extra options" disclosure: **Color** choice (black, brown, green, yellow, pink/red — server enum, exact strings per research doc) and **Amount** (numeric).
- Save → `POST /api/changes/` {child, time, wet, solid, color?, amount?}. time = now.

### 3.3 Sleep **[P]**
- Start/stop timer; note field in running panel. Save → `POST /api/sleep/` {child, start, end, notes?}. Nap flag auto by server rules (don't send).

### 3.4 Notes **[P]**
- Opens text panel; Save (disabled when empty) → `POST /api/notes/` {child, note, time=now}.

### 3.5 Pumping **[P]**
- Start/stop timer; amount (mL) + note fields. Save → `POST /api/pumping/` {child, start, end, amount?, notes?}.

### 3.6 Tummy time **[P]**
- Start/stop timer; milestone text + note. Save → `POST /api/tummy-times/` {child, start, end, milestone?, notes?}.

### 3.7 Duplicate guard at save **[N]**
Before saving, if the same type already has an entry for this child within the last **4 minutes** (server data or local), show one calm line: e.g. "Wet diaper logged 4 min ago. Add anyway?" — **Add is the default action**; "View last entry" secondary. Never blocks; never silent-dedupes.

### 3.8 Conflict on save (overlap) **[P→N bridge]**
Server may reject with `non_field_errors` (overlapping Sleep/Feeding/Pumping/TummyTime; future times; start≥end — research doc §conflicts). Online: show a **translated, parent-language reason** (never raw server-speak; overlap errors MUST name the clashing entry, e.g. "This overlaps the 1:15–2:00 PM nap" — parse the conflicting-entry id from the server message when possible and link to it) in a non-destructive dialog; entry form stays open with values intact. Offline: entry goes to FAILED state (§6.3).

## 4. Timeline entries

### 4.1 Headline formats (MUST be consistent app-wide)
- Feeding: "Bottle · 120 mL" (method · amount) + duration
- Diaper: "Wet" / "Solid" / "Wet + Solid" (+ color chip if set)
- Sleep: "Sleep" + duration
- Note: first line of note text
- Pumping: "Pump · 85 mL" + duration
- Tummy time: "Tummy time" + duration (+" · milestone" if set)

### 4.2 Interactions **[N — the core new feature]**
- **Tap** → Quick Edit sheet (§5). (Original app has no tap action; this is deliberate.)
- **Long-press** → opens the SAME event on the Baby Buddy web UI: `{serverUrl}/{uiPath}/{serverId}/` (uiPaths: `feedings`, `changes`, `sleep`, `notes`, `pumping`, `tummy-time`). MUST keep this mechanic at all times — the web app is the superset UI. **[P]**
  - Entry without serverId (offline-created, unsynced) → long-press is inert; badge explains why (§6).
- **Delete**: in the Quick Edit sheet (destructive, confirm dialog, then DELETE). Local-only failed entries delete only locally. **[P parity: original deletes via web; we add in-app delete as part of conflict resolution]**

## 5. Quick Edit sheet (Feature 1a) **[N]**

Material 3 bottom sheet, type-appropriate fields. Design goals: fix a time in ≤3 interactions; never more fields than the quick action itself.

- **Time editing** (all types): start time (and end time for timed types) each get a row showing the stored value + stepper buttons **−15m / −1m / +1m / +15m** (15-minute steps primary — parents think in 5/15-minute units) and tap-to-open the platform time picker (date+time). **Moving the start time of a timed entry keeps its duration** (end shifts by the same delta); changing end adjusts duration independently. Constraint feedback inline: end must be after start; no future times; timed entries ≤24h (server rules). Save disabled with reason while invalid.
- **Type fields** mirror §3 exactly (feeding type/method/amount; diaper wet/solid/color/amount; sleep notes; note text; pumping amount/notes; tummy milestone/notes).
- **Save** → PATCH `/api/{type}/{id}/` (online) or queued edit op (offline §6). On success the timeline row updates in place (animated).
- **Conflict on edit** (overlap created by the new times) → same handling as §3.7.

## 6. Offline & sync (Feature 1b) **[N]**

### 6.1 Model
- Room/SQLite-equivalent local DB is the UI source of truth. Every write (create/edit/delete) records an outbox op and applies locally immediately.
- `SyncWorker` drains the outbox oldest-first when connectivity exists; exponential backoff; runs on app open, connectivity regain, and write.
- Local IDs (uuid) + nullable serverId; serverId filled on successful create.

### 6.2 Sync-state badge per entry (timeline + sheets)
- **SYNCED**: no badge (clean look).
- **PENDING**: small muted clock icon + "Will sync" — shown while offline or queued.
- **FAILED**: subdued but noticeable amber/red-outline style + warning icon. Tap opens the failure dialog: server's error reason (e.g. "Another entry intersects the specified time period"), buttons: **Edit** (opens Quick Edit) / **Delete locally** / **Retry**. MUST NOT auto-delete. MUST NOT block other entries' sync.

### 6.3 Scenarios (MUST all pass QA)
1. Server down → create events → badge PENDING → server returns → events replayed in order → badges clear.
2. Server down → overlapping sleep entries created → replay: second rejected → FAILED badge + dialog flow (edit to non-overlap → retries → SYNCED; or delete locally).
3. Airplane mode mid-session → timer started offline keeps ticking → stop → entry PENDING → reconnect → syncs.
4. Kill app offline → relaunch → pending entries and running timers intact.
5. Two devices same child → both create near-duplicate diaper changes → both sync (server allows; no dedup) → visible in timeline (accepted tradeoff per product decision).
6. 401 during sync (key revoked) → pause queue, surface "Session expired — your N unsaved entries are safe on this phone. Sign in and they'll go up." → login → resume automatically.

## 7. Widgets (Feature 2) **[N]**

### 7.1 Quick Actions widget (2×2 or 2×1)
- Buttons: **four configurable slots** (default: Sleep toggle, Bottle, Diaper, Pump) — user picks any quick action per slot in the widget config (§7.3). Tap = same create/toggle semantics as in-app (including "diaper opens app with panel pre-opened" for types needing input).
- Active-timer state: button shows running state (accent + elapsed for sleep if space).

### 7.2 Care Dashboard widget (4×2 or resizable 4×3)
- Rows (configurable set, default all): **Last feed** (X min ago + amount), **Last diaper** (X min ago + wet/solid), **Last nap** (X h ago + duration), **Last pump** (X min ago + amount), plus **Running timer** row when one is active.
- Tap a row → deep link: nap row starts a sleep timer; feed/diaper rows open the app with that quick-action panel open. Running-timer row → opens app to the timer.
- Data: reads the local DB snapshot; refresh on app writes + on sync completion + WorkManager periodic (≥15 min) + system widget update allowance. Counters are minute-granular ("Xm ago"); staleness is acceptable and documented (no per-second widget ticking in beta).

### 7.3 Widget configuration
- One-tap add uses active child. Long-press → Configure: child selection, (dashboard widget) row visibility, and (quick-actions widget) the four button slots.

## 8. Look & feel (Feature 3) **[N]**

- Material 3, dynamic color from wallpaper on Android 12+, tasteful static palette fallback (self-hosted apps shouldn't scream brand).
- Dark theme follows system.
- Motion budget: animated timer counter, sheet slide-up, timeline item insert/remove, sync-badge transitions. Nothing decorative that delays logging.
- Type scale favors glanceability: durations and "time since" values are prominent.
- Original icon set (our own vector art; no copied assets from the original app — its media is attribution-licensed).

## 9. Settings & help **[P/N]**
- Overflow menu: Sign out **[P]**, Help (short slideshow re-using original app's UX-guidance intent, our own content) **[P]**, About (version, licenses, links to both upstream projects) **[P]**.
- Server/account info display (URL + username, read-only in beta).

## 10. Non-functional MUSTs
- Cold start to dashboard ≤2s on Pixel 5-class hardware.
- Timer elapsed accurate across backgrounding (wall-clock based, not tick-count).
- All server field names/enum values exactly per `docs/research/babybuddy-api.md`.
- No crash on: no children, no network, invalid server cert, 500s, malformed data.
- Unit-test the sync engine, mappers, timer logic (framework per implementation).
