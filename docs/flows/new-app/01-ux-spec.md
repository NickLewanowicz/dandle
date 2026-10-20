---
title: "UI shell spec"
---

# babybuddy-next — UI/UX Specification (v0.1 BETA)

> Companion to `00-functional-spec.md` (canonical). This document describes **what the user sees and feels**, per screen and component: layout, states, copy, motion. It is framework-agnostic — engineers map each named component to their toolkit's equivalent. Colors are named as Material 3 **roles** (primary, secondaryContainer, surfaceVariant, error, errorContainer, onSurfaceVariant…), never hex. Section numbers (§) reference the functional spec.
>
> Standing rules for every screen: touch targets ≥ 48dp (the six quick actions and all Save buttons ≥ 56dp tall); durations, counters, and "time since" values are the most prominent text on their surface, in tabular figures; error color is reserved for errors — never decoration; copy is short, plain, and never blames the user.

---

## 1. Login screen (§1.1)

**User goal:** enter server + credentials once, never think about it again.

### Layout (top to bottom)
```
┌─────────────────────────────────────────────┐
│                                             │
│                (app mark)                   │
│               Baby Buddy                    │
│    Track feeds, sleep, and care.            │
│                                             │
│  SERVER URL                                 │
│  [ myserver.example.com               ]     │
│  Will use https://myserver.example.com      │  ← normalization hint
│                                             │
│  USERNAME                                   │
│  [                                    ]     │
│                                             │
│  PASSWORD                                   │
│  [                                    ]     │
│                                             │
│  (  error banner appears here when needed  )│
│                                             │
│  ┌───────────────────────────────────────┐  │
│  │               Sign in                 │  │  ← full-width, 56dp
│  └───────────────────────────────────────┘  │
└─────────────────────────────────────────────┘
```
- Single centered column, max width ~400dp. No tabs, no QR login in beta (not in §1).
- **ServerUrlField** (`LoginField`): accepts bare host; live supporting text shows the normalized URL **only while it differs from the typed text** (scheme added, trailing `/` trimmed). This prevents a whole class of "why won't it connect" errors at zero cost.
- **Sign in** button: disabled until all three fields are non-empty (matches original app).
- Password field: obscured, with visibility toggle. IME action = Sign in.

### States
| State | Treatment |
|---|---|
| **Idle** | Fields enabled, button disabled until valid. |
| **Signing in** | Button content → small progress indicator + "Signing in…"; fields read-only. |
| **Unreachable server** | Error banner (errorContainer bg, onErrorContainer text) above button: "Couldn't reach the server — check the URL and your connection." ServerUrlField also shows error styling. |
| **Bad credentials (401)** | Error banner: "Wrong username or password." Username/password fields get error styling; URL field stays clean (it resolved fine). |
| **Zero children (200)** | Error banner: "No children found. Create one in the Baby Buddy web app, then retry." No field error. |
| **Session expired** (returned from dashboard after 401, §6.3.6) | Neutral info banner: "Session expired. Sign in again." Fields pre-filled with stored server URL + username. |
| **Cleartext warning** (scheme `http://`, on submit) | Modal dialog. Title: "Unencrypted connection". Body: "Your credentials and data travel in clear text. Continue?" Buttons: **Continue** (proceeds with sign-in), **Cancel**. Default styling — do not style Continue as an error. |

All banner copy is dismissed on next submit attempt. No stack traces, no HTTP codes in copy.

---

## 2. Dashboard (§2)

**User goal:** open the app mid-night, one hand, and in under a second see what's running, log an event, and confirm it landed.

### Layout (top to bottom)
```
┌─────────────────────────────────────────────────┐
│ Baby Buddy              [ Emma ▾ ]        ( ⋮ ) │ ← header bar
│                                    (Offline) │ ← SyncStatusChip (§5), when needed
│ ┌─────────────────────────────────────────────┐ │
│ │ (moon) Sleep                    00:47:12    │ │ ← ActiveTimersStrip
│ │        Running                  [Save]  ✕   │ │
│ └─────────────────────────────────────────────┘ │
│                                                 │
│  (feed)      (diaper)     (moon)                │
│  Feeding     Diaper       Sleep                 │ ← QuickActionsGrid 3×2
│  (note)      (pump)       (crawl)               │
│  Notes       Pumping      Tummy time            │
│                                                 │
│  Today                            Oct 14 (yest.)│ ← DayHeader rows
│  ─────────────────────────────────────────────  │
│  (bottle) Bottle · 120 mL                14:32  │
│          Great latch after feed  (Will sync) 12m│
│  (diaper) Wet + Solid  (yellow ●)        13:05  │
│  (moon)   Sleep                          09:12  │
│          …                               45m    │
│  ─────────────────────────────────────────────  │
│  Yesterday                                      │
│  …                                              │
│              ( pull-to-refresh at top )         │
└─────────────────────────────────────────────────┘
```

### Header bar
- Left: app name "Baby Buddy" (title-medium). Right: **ChildSwitcherChip** — current child's first name + dropdown arrow → dropdown menu of all children; selection persists across restarts (per child slug/id, §2.1). With exactly one child the arrow is hidden but the chip remains (consistent tap target). Zero children (deleted server-side later): chip reads "No child", quick actions disabled, timeline empty state shows the §1.1 zero-children copy.
- Overflow menu (⋮): **Sign out**, **Help**, **About**. Sign out → confirm dialog: title "Sign out?", body "This clears the stored key and local data on this device. Data on the server is untouched." Buttons: Cancel / Sign out (destructive color on text). About includes a read-only "Connection" section: server URL + username (§9).

### ActiveTimersStrip (only while ≥1 timer runs)
One **TimerCard** per running timer, newest first. Anatomy, left to right:
1. Type icon in a tonal circle (primaryContainer).
2. Text column: type label ("Sleep") + live elapsed counter, headline-size tabular figures, format `HH:MM:ss` — the single most glanceable element in the app.
3. **Save** button (filled, 48dp tall): stops the timer and logs with current panel values.
4. Discard icon-button (✕, 48dp): runs the stop guard (§3 common rules).
- Tapping the card body opens that timer's quick-action panel. If the timer's start op hasn't reached the server yet (offline start), the card shows the muted PENDING mark (§5) beside the label.
- Timers restored after process death reappear here with wall-clock-correct elapsed time; field drafts (amount/notes) rehydrate into the panel on open.

### QuickActionsGrid
- Exactly six buttons, canonical order: **Feeding, Diaper, Sleep, Notes, Pumping, Tummy time** (§2.3). Phones: 3×2 grid; each cell = 64dp tonal circle (surfaceVariant) with 28dp icon + label below (body-medium). Tablet/landscape: still 6 cells, grid grows to 6×1.
- **Iconography direction** (original artwork — no copied assets, §8): filled style, simple recognizable silhouettes that survive 24dp: Feeding = baby bottle; Diaper = folded diaper; Sleep = crescent moon; Notes = pencil on paper; Pumping = breast pump; Tummy time = baby propped on forearms. Icon fill stays monochrome (onSurfaceVariant idle / onPrimaryContainer active) — type identity comes from label + position, not per-type hues.
- **Running state** (timer types): the circle switches to primaryContainer/onPrimaryContainer and gains a small static dot (primary) at its top-right. Tap re-opens the running panel (never starts a duplicate, §3). No pulsing, no label swap.

### Timeline
- **TimelineRow** anatomy, left to right:
  1. Leading type icon, 40dp tonal circle (same icon set).
  2. Headline (body-large, per §4.1 — restated below).
  3. Secondary line when present: note preview, 1 line ellipsized (onSurfaceVariant), or the sync badge (see below) after it.
  4. Trailing column: line 1 shows the start–end range (`21:38–22:00`) for ANY timed entry longer than 5 minutes or crossing local midnight — plain time-of-day (`14:32`, locale format) only for shorter entries; line 2 is the duration for timed types (onSurfaceVariant, `12m` / `1h 05m`; sub-minute renders `<1m`, never bare seconds like `45s`).
- **Sync badge** lives at the end of the secondary line: PENDING = muted clock icon + "Will sync"; FAILED = errorContainer pill, see §5. SYNCED = nothing.
- **Headline formats** (exact, app-wide):
  - Feeding: `Bottle · 120 mL` — method · amount; the `· amount` segment is omitted when amount is null.
  - Diaper: `Wet` / `Solid` / `Wet + Solid`, plus inline color swatch dot + color name when color is set.
  - Sleep: `Sleep`
  - Note: first line of the note text (up to 2 lines, ellipsized).
  - Pumping: `Pump · 85 mL`
  - Tummy time: `Tummy time` · milestone when set (`Tummy time · Rolled over`).
- **DayHeader**: full-width row, label "Today" / "Yesterday" / locale date ("October 14"), title-medium on surface. Not sticky — beta restraint.
- **Interactions**: tap row → Quick Edit sheet (§5 of functional spec, our §4). Long-press → opens `{serverUrl}/{uiPath}/{id}/` in the browser (§4.2 — always keep). Entries with no serverId: long-press inert + snackbar: "This entry hasn't synced yet." One-time hint (first timeline render, dismissable snackbar): "Long-press an entry to open it in the Baby Buddy web app."
- **Pull-to-refresh**: platform indicator; triggers a full re-sync; SyncStatusChip shows "Syncing…" while running.
- **Pagination**: loading-older spinner row at the bottom while fetching; nothing at end of list (no "that's all" chatter).
- **Empty state** (fresh server): static line-art illustration (moon + bottle — no animation), text: "Log your first event with a button above."

**Create-flow conflicts (spec §3.8, as-built):** saves are local-first; a server rejection always lands the entry in FAILED state and opens the failure dialog with the entered values intact (Edit / Discard) — the same visual language as offline failures. **Rejected tradeoffs:** horizontal child paging (original) — drop for a dropdown; vertical scroll + pull-to-refresh + row tap already own the gestures. Sticky day headers — nice, but compete with the timer strip for vertical space; the beta list is short. Modal login errors — inline banners keep context and let the user fix the field they're on.

---

## 3. Quick-action panels (§3)

**User goal:** tap a button, land in a panel whose defaults are already right, hit Save — or start a timer without reading anything.

### Common pattern
- The panel appears as an **inline card directly below the quick actions grid** (original app's information architecture), pushing the timeline down. Not a bottom sheet — the sheet is reserved for editing existing entries (§4); different surfaces for create vs. edit keeps the mental model clean.
- Card anatomy: title row (type icon + type name; timer types add the live counter right-aligned, headline-size) → fields → **Save** (filled, full-width, 56dp). A close (✕) on the title row = cancel path (with stop guard for running timers).
- **Save disabled conditions** are the only validation; there is no inline error text in create panels. Defaults make most panels instantly saveable.
- On save the panel closes, the entry is applied locally, and it appears at the top of Today **with the insert animation** — the animation *is* the confirmation. No toast, no checkmark screen.
- Draft persistence: all field values (including panel-open state) persist locally per child+type and clear only on successful save (original behavior, §4.4 of research doc).

### Per-type layout
| Panel | Layout (top→bottom) | Save enabled when |
|---|---|---|
| **Feeding** (timer) | Counter · **Type** chip-row (4 choices, default *Breast milk*) · **Method** chip-row (cascade, default *Bottle*) · **Amount** numeric field, helper "mL — optional for nursing" | Always (defaults valid) |
| **Diaper** (no timer) | **Wet** / **Solid** as two large toggle cards side by side (icon + label; selected = secondaryContainer fill) · "Extra options" disclosure → **Color** swatch-chip row + **Amount** numeric field | ≥1 of Wet/Solid is on |
| **Sleep** (timer) | Counter · **Note** field (optional) | Always |
| **Notes** (no timer) | Multiline text, auto-focused, keyboard raised | Text non-empty (trimmed) |
| **Pumping** (timer) | Counter · **Amount** numeric (mL, optional) · **Note** | Always |
| **Tummy time** (timer) | Counter · **Milestone** text · **Note** | Always |

Details:
- **Feeding cascade**: method options depend on type — *Breast milk* → Left breast, Right breast, Both breasts, Bottle, Parent fed, Self fed; any other type → Bottle, Parent fed, Self fed. Changing type resets an invalid method to Bottle. Chip labels are sentence case; wire values are the exact server enums (display "Fortified milk" is acceptable; wire value stays `fortified breast milk`).
- **Diaper colors**: display labels Black, Brown, Green, Yellow, Pink/red, each a swatch dot + label. Swatches use a fixed content palette (theme-independent) so "yellow" looks yellow in dark mode too — swatches are content, not theme roles. Verify the pink/red wire value against the API doc (research doc lists only black/brown/green/yellow + empty).
- **Notes**: single-line "Save" appears once text is non-empty; time = now, no picker in the create panel (correct later via Quick Edit — that's what it's for).

### Running-timer variant & the >10s stop dialog
- All four timer panels share: live counter (per-second text tick), fields per table above, Save (= stop + log), close ✕ (= cancel path).
- **Stop guard** (§3): tapping the quick-action button again, the strip card's ✕, or the panel's ✕ —
  - Timer **≤ 10s**: discard silently. Rationale: nothing meaningful is lost; a dialog on every mis-tap would be the real friction.
  - Timer **> 10s**: dialog. Title: "Stop timer?" Body: "You've been tracking `{Feeding}` for `{12:34}`." Buttons: **Save** (filled — most likely intent), **Discard** (text, error color — logs nothing, deletes the timer), **Keep** (text — dismiss, keep running). Non-destructive default = Keep.

**Design note (flag for Maintainer):** §3.7 says an online conflict "keeps the entry form open with values intact." Our local-first model (§6.1) closes the form on Save and applies locally immediately — so the conflict surfaces moments later as a FAILED badge + failure dialog whose **Edit** opens the Quick Edit sheet with the original values intact. Same forgiveness, faster happy path. Requires Maintainer sign-off as a deliberate deviation.

---

## 4. Quick Edit sheet (§5)

**User goal:** "the feed actually started at 14:15, not 14:32" — fix a time in ≤3 interactions, change nothing else.

### Anatomy (top to bottom)
```
┌─────────────────────────────────────────────────┐
│                      ──                         │ ← drag handle
│ (bottle) Feeding · Bottle · 120 mL           ✕  │ ← title row: icon + current headline
│                                                 │
│ ( only for FAILED entries: )                    │
│ (!) Couldn't sync: Another entry intersects…   │ ← failure banner
│                                                 │
│ Amount   [ 120      ] mL                        │
│ Type     (Breast milk)(Formula)(Fortified milk) │
│                 (Solid food)                    │
│ Method   (Bottle)(Left breast)(Right breast)    │
│            (Both breasts)(Parent fed)(Self fed) │
│                                                 │
│ START    Today 14:32     [−15m][−1m][+1m][+15m] │ ← StepperTimeRow
│ END      Today 14:44     [−15m][−1m][+1m][+15m] │
│ (!) End time must be after start time.            │ ← inline validation
│                                                 │
│ [    Save    ]              Retry   Delete      │
└─────────────────────────────────────────────────┘
```
- **Title row**: type icon + current headline (same format as the timeline row) + close ✕.
- **Type fields**: mirror the create panel exactly (§3 table) — never more fields than the quick action itself. Chip rows may wrap; compact sizing.
- **StepperTimeRow** (one for Start; one for End on timed types; a single "Time" row for Diaper/Note): label + stored value ("Today 14:32") + four compact stepper buttons **−15m / −1m / +1m / +15m** (15-minute steps are primary — parents think in 5/15-minute units; "started 20 min earlier" is two taps). Tapping the *value* opens the platform date+time picker. Steps cross midnight and day boundaries freely; the value's date prefix updates live. On timed types, moving the **Start** row shifts **End** by the same delta (duration preserved — moving a start never breaks validation or forces a second clock fix).
- **Inline validation** — message below the time rows, error color, with the reason; Save is disabled while invalid:
  - "End time must be after start time."
  - "Entries can't be in the future."
  - "Timed entries can't be longer than 24 hours."
- **Action row**: **Save** (filled, left) · **Retry** (text, only when the entry is FAILED) · **Delete** (text, error color, right).
  - Save → PATCH online, or queued edit op offline (§6); row updates in place with the badge-transition animation.
  - Retry → re-queues the failed op; badge → PENDING.
  - Delete → confirm dialog: title "Delete entry?", body "This removes it from the app and the server." (local-only FAILED variant: "This removes it from this device. It was never synced.") Buttons: Cancel / Delete (destructive).
- **Why ≤3 interactions holds:** steppers are 1 tap per minute; picker path is value-tap → picker → done = 3.

### Conflict dialog (edit creates an overlap, or server rejects the PATCH)
- Title: "Can't save changes". Body: the reason translated to parent language (server overlap errors MUST be parsed to name the clash, e.g. "This overlaps the 1:15–2:00 PM nap — Edit times or Discard"). Buttons: **Edit times** (filled-tonal — closes dialog, sheet stays open with all values intact, scrolls to the time rows) / **Discard changes** (text — reverts to stored values). Never auto-close the sheet, never lose typed values.

**Create-flow conflicts (spec §3.8, as-built):** saves are local-first; a server rejection always lands the entry in FAILED state and opens the failure dialog with the entered values intact (Edit / Discard) — the same visual language as offline failures. **Rejected tradeoffs:** free-text time entry — pickers + steppers are fat-finger-proof at 3 a.m.; a duration stepper on the End row ("−5m" moves end, not duration) — absolute times match how the server validates and how parents think ("it ended at 14:44").

---

## 5. Offline & sync visual language (§6)

**User goal:** trust that everything they logged is safe and will land — without reading anything or feeling alarmed.

### Per-entry badges (SyncBadge)
| State | Treatment |
|---|---|
| **SYNCED** | No badge. The clean default. |
| **PENDING** | Muted: small clock icon + "Will sync", onSurfaceVariant, no background. Says "safe, just not there yet." |
| **FAILED** | Small pill: **errorContainer fill, onErrorContainer content** — warning icon + "Sync failed". Noticeable (the only filled badge in the app) but not a red row or a red banner; the timeline stays calm. Tap the pill (or the row) → failure dialog. |

The badge sits in the timeline row's secondary line and in the Quick Edit sheet title area; identical treatment everywhere (§4.1 consistency rule).

### Failure dialog (tap FAILED badge)
- Title: "Couldn't sync this entry". Body: the server's reason verbatim, e.g. "Another entry intersects the specified time period"; fallback when no reason is parseable: "The server rejected this entry. Edit it or try again." Buttons: **Edit** (filled — opens Quick Edit with values intact; the common fix for overlap), **Retry** (text), **Delete locally** (text, error color → the §4 confirm dialog). Never auto-delete; never blocks other entries' sync.

### SyncStatusChip (header strip, under the header bar)
Answers "is my data safe?" in one glance; hidden when everything is fine.
| State | Treatment | Copy |
|---|---|---|
| Hidden (default) | — | — |
| Offline | Cloud-off icon, surfaceVariant pill, onSurfaceVariant | "Offline" |
| Syncing | Small progress spinner, surfaceVariant pill | "Syncing…" |
| N failed | Warning icon, errorContainer pill, onErrorContainer | "1 failed" / "3 failed" |

Tap the chip → "Sync now" (drains the outbox). The chip co-exists with the PENDING badges: badges say *what*, chip says *how many / how healthy*.

---

## 6. Widgets (§7)

**User goal:** log or glance from the home screen without unlocking into the app.

### Quick Actions widget — 2×2 (§7.1)
Four cells, 2×2: **Sleep, Bottle, Diaper, Pump**. Each cell: 28dp icon + label below, on a surface-colored rounded background; whole cell is one tap target with a platform ripple. Idle cells add a subdued second line under the label — the last event of that type as a compact relative time ("45m" / "1h30m" / "2d"; nothing when there is no history).
```
┌──────────────────────────────────────┐
│  ┌──────────────┐  ┌──────────────┐  │
│  │   (moon)     │  │   (bottle)   │  │
│  │   Sleep      │  │   Bottle     │  │
│  └──────────────┘  └──────────────┘  │
│  ┌──────────────┐  ┌──────────────┐  │
│  │   (diaper)   │  │   (pump)     │  │
│  │   Diaper     │  │   Pump       │  │
│  └──────────────┘  └──────────────┘  │
└──────────────────────────────────────┘
```
Running variant — the Sleep cell shows the running state: highlighted in the type's semantic color (not a generic accent), with a live elapsed label ("Sleep · 12m" — minute granularity, no per-second ticking); the idle last-line is hidden while running:
```
┌──────────────────────────────────────┐
│  ┌──────────────┐  ┌──────────────┐  │
│  │ ▒(moon)▒▒▒▒▒ │  │   (bottle)   │  │
│  │ Sleep · 12m  │  │   Bottle     │  │  ← Sleep running: tap stops+saves
│  └──────────────┘  └──────────────┘  │
│  ┌──────────────┐  ┌──────────────┐  │
│  │   (diaper)   │  │   (pump)     │  │
│  │   Diaper     │  │   Pump       │  │
│  └──────────────┘  └──────────────┘  │
└──────────────────────────────────────┘
```
Tap semantics: Sleep toggles the sleep timer in place (start / stop+save); Bottle, Diaper, Pump open the app with that quick-action panel pre-opened (types needing input). 2×1 fallback: same four cells in a 4×1 row, labels dropped, icon-only (running dot carries state).

### Care Dashboard widget — 4×2 (§7.2)
Header line (child first name, small) + up to 4 **WidgetRow**s. Row anatomy: leading type icon · "Last feed" · time-ago + detail (trailing). Minute-granular counters ("12m"), refreshed on app writes, sync completion, and periodic background updates — staleness is acceptable and documented (§7.2).
```
┌──────────────────────────────────────────────┐
│  Emma                                        │
│  (bottle)  Last feed       12m · 120 mL      │
│  (diaper)  Last diaper     34m · Wet         │
│  (moon)    Last nap        2h  · 45m         │
│  (pump)    Last pump       3h  · 85 mL       │
└──────────────────────────────────────────────┘
```
With a timer running, the **Running timer** row takes the FIRST slot, accented (primaryContainer):
```
│  (moon) ▒ Asleep since 13:00 · 12m elapsed  ▒│
│  …                                           │
└──────────────────────────────────────────────┘
```
- Tap targets: ALL info rows open the app — feed/diaper/pump rows deep-link with that panel pre-opened, and no timer is ever started from the widget; only the running row routes to the stop guard. Rows have no ripple requirement — platform default highlight suffices.
- Resizable 4×3: same rows with more breathing room and full detail lines; no new content types in beta.
- **Configuration** (long-press → Configure): child selection + per-row visibility (dashboard widget). One-tap add uses the active child.
- **Dark/light**: follows system; surface background, onSurface text, dynamic-color accents where the platform exposes them. Both variants must be legible at arm's length on a nightstand — minimum body-size text, no thin weights.

---

## 7. Motion budget (§8)

**Rule:** every animation must communicate a state change the user caused or needs to know about. If it only decorates, cut it.

| Motion | Duration | Communicates |
|---|---|---|
| Logging panel expand/collapse | 200 ms fade + height | a quick action opened/closed |
| Bottom sheet slide up/down | 250 ms (platform spec) | edit mode entered/left |
| Timeline row insert/remove | 200 ms | save succeeded / delete done — this is the save confirmation |
| Sync badge transition | 150 ms fade | entry left PENDING→SYNCED, or FAILED appeared |
| SyncStatusChip swap | 150 ms fade | connectivity/sync state changed |
| Pull-to-refresh spinner | platform default | manual sync running |
| Timer counter / widget minute tick | none — plain text update | time elapsing (the counting *is* the animation) |

**Explicitly forbidden:** launch/splash animation; decorative parallax; save confetti, button bounce, or success checkmarks (the timeline insert does that job); looping idle animations (the empty-state illustration is static); any motion longer than 300 ms; sound/haptics beyond platform defaults on text input.

---

## 8. Component inventory

Engineers map these 1:1 to framework components; states are exhaustive per component.

| Component | Purpose | States |
|---|---|---|
| `LoginScreen` | First-run + re-auth gate | idle · signing-in · error-unreachable · error-credentials · error-no-children · session-expired-notice · cleartext-confirm-dialog |
| `LoginField` | Server URL / username / password input | idle · normalized-hint (URL only) · error |
| `ChildSwitcherChip` | Active child selection (persisted) | single-child · multi-child-menu · no-child |
| `OverflowMenu` | Sign out / Help / About | closed · open · signout-confirm-dialog |
| `SyncStatusChip` | Header strip sync health | hidden · offline · syncing · n-failed |
| `TimerCard` | One running timer in the strip | running · saving · start-pending (PENDING mark) |
| `QuickActionButton` | One of the six grid buttons | idle · running (dot + tonal swap) · panel-open · disabled (no child) |
| `QuickActionPanel` | Inline create card (base) | open · saving · closed |
| `FeedingPanel` / `SleepPanel` / `PumpingPanel` / `TummyPanel` | Timer-type create fields | running-timer variant (counter + fields) · fields-valid (Save on) |
| `DiaperPanel` | Non-timer create | no-toggle-on (Save disabled) · valid · extra-options-collapsed · extra-options-expanded |
| `NotePanel` | Quick note create | empty (Save disabled) · valid |
| `ChoiceChipRow` | Feeding type/method, diaper color | unselected · selected · option-set-n (cascade for method) |
| `ToggleCard` | Wet / Solid | off · on |
| `DisclosureSection` | "Extra options" | collapsed · expanded |
| `TimelineRow` | One event | synced · pending · failed (badge tap → FailureDialog) · no-server-id (long-press inert) |
| `DayHeader` | Day grouping label | static |
| `EmptyTimeline` | Fresh-server state | static illustration + copy |
| `PaginationFooter` | Older-page loader | loading · idle-hidden |
| `SyncBadge` | Per-entry sync state | synced-absent · pending · failed |
| `QuickEditSheet` | Edit existing entry | open · invalid-reason (Save disabled) · saving · failed-banner · conflict-dialog · delete-confirm-dialog |
| `StepperTimeRow` | Time value + −5/−1/+1/+5 + picker | idle · picker-open · invalid-reason |
| `FailureDialog` | FAILED entry resolution | reason-shown · fallback-reason · edit/retry/delete-pending |
| `ConflictDialog` | Overlap / server rejection on save | server-reason · discard-confirm |
| `ConfirmDialog` | Destructive-action guard | signout · delete-synced · delete-local-only |
| `WidgetQuickActions` (2×2 / 2×1) | Home-screen logging | idle · sleep-running (accent + elapsed) |
| `WidgetRow` (Care Dashboard) | One "last X" line | idle · running-timer (accented, tappable) |
| `WidgetConfigure` | Long-press config | child-select · row-visibility |

---

*End of spec. Deviation flag for Maintainer: §3 design note on local-first conflict surfacing vs. functional-spec §3.7 form-open behavior.*
