---
title: "Original app: Overview"
---

# Baby Buddy for Android v2.6.4 — App Map & Overview

Black-box QA study of the installed app `eu.pkgsoftware.babybuddywidgets` (v2.6.4, versionCode 54)
against a Baby Buddy dev server (`http://10.0.2.2:8000` from the emulator, `http://localhost:8000`
from the host). Session date: 2026-10-16. All server effects in this study were verified live via
`GET/POST/DELETE http://localhost:8000/api/...` with `Authorization: Token …`.

Related files: `01-login.md` … `12-edge.md` (one per area), `99-discrepancies.md` (vs.
`docs/research/existing-app-architecture.md`). Screenshots:
`docs/screenshots/existing-app/<area-folder>/NN-name.png`.

---

## 1. Screen inventory / navigation graph

Single-Activity (`MainActivity`) + Jetpack Navigation. All screens verified:

```
LoginFragment ("Login to Baby Buddy")
 ├─ (QR) QRCodeLoginFragment [camera; NOT exercised — no QR source in test env]
 └─ LOGIN (after http-warning dialog + credential scrape) ──► LoggedInFragment
       LoggedInFragment = ALL-IN-ONE dashboard (toolbar + per-child ViewPager pages)
        ├─ overflow menu (3-dot) ──► Help slideshow fragment      [back returns]
        ├─ overflow menu ──► Settings fragment (single Dark/Light mode pref)
        ├─ overflow menu ──► Logout (immediate, no confirm) ──► back to LoginFragment
        ├─ overflow menu ──► Contact developer fragment (form + SEND/BACK)
        ├─ overflow menu ──► About Baby Buddy for Android fragment
        ├─ horizontal swipe on dashboard ──► previous/next child page (ViewPager)
        └─ long-press timeline entry ──► leaves app: ACTION_VIEW {server}/{uiPath}/{id}/
```

Notes:
- BACK from the dashboard exits the app (LoggedIn is root; no intermediate screens).
- No bottom navigation, no side drawer, no tabs. The only global navigation elements are the
  toolbar title (child name) and the 3-dot overflow menu.
- Toolbar title always shows the current child's first+last name ("Milo Rivera" / "Zoe Rivera").

## 2. Dashboard layout (per-child page, top→bottom)

One ScrollView per child inside a horizontal pager (`id=babyViewPagerSwitcher`):

1. **Six quick-action buttons** — auto-wrapping grid, 2 rows x 3, centered, ~64dp icons.
   Row 1: Feeding (baby bottle), Diaper change (diaper), Sleep (crescent moon "Zzz").
   Row 2: Note (note+pencil), Pumping (breast pump), Tummy time (crawling baby).
   Each button has an inactive (light gray) and active (activity color: feeding pink,
   diaper yellow, sleep lavender, note teal, pumping magenta, tummy green) art variant.
2. **Inline logging panel** (only while an activity is "open"; inserted below the buttons,
   pushes the timeline down; scroll position preserved).
3. **"Timeline" separator** (horizontal lines + centered label).
4. **Timeline list** — merged reverse-chronological entries, each row: colored type icon,
   text line(s), red trash button on the right. Delete button always visible per row.

## 3. Six quick actions

| # | Button (resource id) | Icon | Behavior on tap | Server calls | Endpoint(s) verified this session |
|---|---|---|---|---|---|
| 1 | Feeding (`feeding*Button`) | baby bottle | opens timer panel; creates server timer `Feeding-BBapp:1`; type/method selectors + amount + note | create timer; on save POST entry + DELETE timer | `POST api/timers/`; `POST api/feedings/`; `DELETE api/timers/{id}/` |
| 2 | Diaper change (`diaper*Button`) | diaper | opens non-timer panel; Wet/Solid toggles (+ color/amount under "+"); save when a toggle is on | none until save | `POST api/changes/` (fields `time,wet,solid,color,amount`) |
| 3 | Sleep (`sleep*Button`) | crescent moon Zzz | opens timer panel; creates `Sleep-BBapp:2`; note only | create timer; save = POST + DELETE | `POST api/timers/`; `POST api/sleep/`; `DELETE api/timers/{id}/` |
| 4 | Note (`notes*Button`) | note with pencil | opens plain text panel; save appears when text non-empty | none until save | `POST api/notes/` (field `note`, `time`) |
| 5 | Pumping (`pumping*Button`) | breast pump | opens timer panel; creates `Pumping-BBapp:4`; amount (default 0.0, not nullable) + note | create timer; save = POST + DELETE | `POST api/pumping/` |
| 6 | Tummy time (`tummyTime*Button`) | crawling baby | opens timer panel; creates `Tummy time-BBapp:3`; note only (stored as `milestone`) | create timer; save = POST + DELETE | `POST api/tummy-times/` |

Timer name suffixes are **fixed per activity**: feeding=1, sleep=2, tummy time=3, pumping=4
(verified: `Sleep-BBapp:2`, `Feeding-BBapp:1`; "Tummy time-BBapp:3"/"Pumping-BBapp:4" inferred
from the same scheme — the create call always used that exact name).

## 4. Server-side API call log for this session (verified by curl after each UI action)

| When (UI event) | Request | Observed effect |
|---|---|---|
| Login (password flow) | web-session scrape: `GET login/`, `POST login/`, `GET api/profile` (see 01-login.md) | token obtained; app then uses token auth |
| Dashboard visible | `GET api/children/` (+ cached), `GET api/timers/?child=`, `GET api/{sleep,feedings,changes,pumping,notes,tummy-times}/` | merged timeline; ~5s polling implied by refreshes during the session |
| Tap Feeding/Sleep/Pumping/Tummy time | `POST api/timers/ {child, name:"<Type>-BBapp:<n>", start}` | timer appeared server-side within ~1s of tap |
| Save sleep (after 4:17) | `POST api/sleep/` + `DELETE api/timers/1/` | sleep id=6: start == timer start exactly; end = save moment; notes persisted |
| Cancel timer \<10s | `DELETE api/timers/{id}/` (no dialog) | timer gone, nothing logged |
| Cancel timer >10s → "Stop" | `DELETE api/timers/{id}/` after "Stop timer?" dialog | timer gone, nothing logged |
| Cancel timer >10s → "Keep" | (no request) | timer kept running |
| Save feeding | `POST api/feedings/` + DELETE timer | id=10: `type:"formula", method:"bottle", amount:3.0` |
| Save diaper | `POST api/changes/` | id=8: `wet:true, solid:false, color:"brown", amount:null` |
| Save note | `POST api/notes/` | id=4: `note:"QA note from flow test"` |
| Save pumping | `POST api/pumping/` + DELETE timer | id=3: `amount:1.0` |
| Save tummy time | `POST api/tummy-times/` + DELETE timer | id=3: `milestone:"Rolled over!"` |
| Timeline row long-press | none (leaves app via `ACTION_VIEW http://10.0.2.2:8000/sleep/6/`) | Chrome opened `…/login/?next=/sleep/6/` (web session not shared) |
| Timeline trash → Delete | `DELETE api/notes/4/` | notes count 4→3 |
| Logout | local only (clear token/cookies/drafts) | server token stays valid (used by curl afterwards) |

Entry counts before → after session: sleep 5→6, tummy-times 2→3, feedings 9→10, pumping 2→3,
changes 7→8, notes 3→3 (one QA note created then deleted). Zero leftover timers.

## 5. Cross-cutting behaviors that MUST be replicated

- Save semantics for timer activities: entry `start` = timer's server start (exact second),
  `end` = "now (server clock)" at save; timer deleted as part of save (not stopped).
- Server-time discipline: displayed times convert UTC ↔ device-local; a future-dated seeded note
  displayed as "6:54 PM" while device clock was 5:07 PM — pure pass-through of server data.
- Draft persistence: notes/toggles/amounts survive app restarts while a panel is open (verified
  implicitly: re-opened panels retained open state and running timers after relaunch).
- Selected child persists across logout/login (relaunch landed on Zoe after logout).
- Timeline entry text formats: `M/D/YY  h:MM AM/PM`, ranges `start - end (H:MM)`, `Amount: N`
  (trailing `.0` stripped in list display, e.g. "Amount: 3" for 3.0), notes on second line.
- Duration shown for entries ≥ 1 min as `(0:04)` etc.
