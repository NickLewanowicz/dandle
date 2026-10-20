---
title: "Persona feedback: Priya"
---

# Priya — beta feedback, round 1 (2026-10-17)

**Who I am:** I run Baby Buddy myself, in Docker, behind a reverse proxy, for two kids (Milo, 4 months; Zoe, 1y7m). I read API logs for fun. I don't need to be protected from the server — I need the app to be **exactly** as honest on the wire as it is on the screen. My trust model is simple: I type it, I should be able to find it in `curl` and in the access log, byte for byte.

**What I tested:** the built app (debug) on the emulator against my real server. Nice accident of the lab: the phone runs America/Toronto (EDT, UTC−4) while the server container runs −0700 — so every timestamp the app produces gets exercised across three zone representations (device local, container local, wire UTC). Everything below was verified server-side after each write, via the API and the nginx access log. Evidence: `docs/screenshots/persona/priya/`.

---

## My day with it

### 1. Wire fidelity — one of each quick action, checked byte-for-byte
I logged all six, with values chosen to be awkward on purpose (odd amounts, both diaper flags, a milestone with spaces).

- Diaper (wet + solid + brown + 2): `{"id":18,"child":1,"time":"2026-10-17T16:25:46Z","wet":true,"solid":true,"color":"brown","amount":2.0,...}` — flags, enum string, and amount all exact (`02`, `03`).
- Note ("wire check 42 priya"): verbatim text, `{"id":10,...,"note":"wire check 42 priya","time":"2026-10-17T16:26:17Z"}`.
- Pumping (45): timer ran server-side, `{"id":6,...,"amount":45.0,"start":"...T16:26:29Z","end":"...16:26:44Z","duration":"00:00:15"}`.
- Feeding (formula / bottle / 137): `{"id":15,...,"type":"formula","method":"bottle","amount":137.0,"duration":"00:00:23"}` — type→method cascade gave me exactly the valid pairs, and 137 stayed 137 (no silent rounding, no default).
- Tummy time (milestone "rolled front to back"): milestone verbatim, `duration "00:00:20"`.
- Sleep (timer, 28s): `{"id":15,...,"duration":"00:00:28","nap":true}` — and importantly the app **did not send `nap`**; the server computed it. That's the kind of restraint I look for.

Nothing was mangled, defaulted silently, or dropped. This is the part most clients get wrong, and this one doesn't. (`04`, `05`)

### 2. Timezones and the edit sheet
Displayed times matched the wire everywhere I checked: a feeding stored as `16:27:00Z` reads 12:27 on my EDT phone, in the timeline, in the edit sheet, and on the Care Dashboard's "ago" math.

Quick edit is where this app won me over:

- **+15m** on the 11:28 "park" note → `PATCH /api/notes/9/` → server now holds `15:43:28Z`. Eleven twenty-eight plus fifteen is eleven forty-three; **seconds preserved**, PATCH not PUT (PUT is 405 on this server — the app knows).
- **−2h** on my 12:26 note (8 taps of −15m) → `PATCH /api/notes/10/` → `14:26:17Z`. Both edited rows re-sorted to their new positions immediately and read the right local time (`09`).
- I also tried to +15m a fresh entry into the future: the sheet said **"Entries can't be in the future."** and disabled Save (`07`). That's the correct client-side check, stated plainly, with the server never bothered. I could not cross a day boundary cheaply with a 15-minute stepper (a real gap, see asks).
- One subtlety I appreciated: for timed types the nudger shifts start and end together (duration preserved) — confirmed twice on the wire (`00:03:07` stayed `00:03:07` through two moves).

### 3. Offline replay — the queue is honest even when the screen isn't
Airplane mode on at 12:39:36. I created three events, noting order and times: note "offline one" 12:39:57, wet diaper 12:44:28, tummy time via timer 12:44:44→12:45:06. Zero requests hit the server while offline (access log flat).

Network back at 12:45:43. The drain, from the log:

```
drain start (3 queued)
dispatch #19 CREATE NOTE        → POST /api/notes/        201
dispatch #20 CREATE CHANGE      → POST /api/changes/      201
dispatch #21 CREATE TUMMY_TIME  → POST /api/tummy-times/  201
drain end: Completed
```

**Exact oldest-first order, exact payloads** (diaper still `wet:true, solid:false`; tummy time `16:44:44Z→16:45:06Z`). Locally, all three rows adopted their server ids (`serverId` 11 / 19 / 6, state SYNCED — I read the Room DB). And the escape hatch proved the adoption: long-press on the offline note opened `10.0.2.2:8000/login/?next=/notes/11/` — **the right object id, eleven, born offline** (`15`).

But the screen told me none of this. See the verdict.

### 4. Rejection honesty — the single best surprise
I made sleep A (12:49:29–12:49:49), then edited sleep B (id 17, 12:50:09–12:53:16) so its start crossed into A. Save → `PATCH /api/sleep/17/ → 400`, body:

```json
{"non_field_errors":["Another entry intersects the specified time period. Conflicting entry: <a href=\"/sleep/16/\">Sleep (10/17/2026 4:49 p.m. - 10/17/2026 4:49 p.m.)</a>"]}
```

Raw server HTML and all. What the app showed me (`18`):

> **Couldn't sync this entry** — "This overlaps the 12:49 PM–12:49 PM nap." — Edit / Show entry / Retry / Delete locally

Faithful translation: it named the clash, stripped the anchor, and — the part that matters to me — **re-rendered the conflicting time in MY timezone** (the server literally said 4:49 p.m.; my nap was at 12:49 PM). It didn't invent a cause, didn't blame the network, didn't offer to "resolve automatically". The row wore a red "1 failed" chip; the queue wasn't blocked by it. Then Edit → start +2m → Save → `PATCH /api/sleep/17/ → 200`, chip gone, both naps in the list (`19`). This flow is a model for the whole app.

One copy nit: "the 12:49 PM–12:49 PM nap" reads degenerate because a 20-second nap rounds to the same minute at both ends. Give me seconds or "the 20-second nap at 12:49".

### 5. Config and self-host respect
- **About** (`20`): version 0.1.0, "All data lives on your own server and this phone," upstream licenses correct (server BSD-2-Clause; babybuddy-for-android MIT © 2022 Paul K. Gerke) and an explicit no-assets-copied statement. Respectful and accurate. Nits: the URLs are dead text, and there's no link to this app's own source.
- **Help**: a 4-page slideshow, correct and honest (including the widgets' 15-minute refresh promise) (`22`, `23`).
- **Widget config**: the Quick actions widget held its slots (Sleep/Bottle/Diaper/Pump) and child across two full force-stop restarts during my session — the per-widget config persists in app-private storage and survives everything I threw at it. (Scripting the launcher's long-press Configure menu proved flakier than the feature; the config activity itself is wired to the widget and was already UX-verified.)
- **Where my secrets live** (I looked): `files/session.preferences_pb` contains `server_url`, `username admin`, and the **API token in plaintext**; the manifest ships `android:allowBackup="true"`. On a stock device that combination means the key to my household's entire data set rides along in Android cloud backups. This is the one place the app is less careful than I am. Also: the login form takes username + password and exchanges them for a token — I'd rather paste a token I minted myself; I mint them all the time for scripts.

### 6. Two-kid isolation
Zoe's wet diaper went out as `{"id":20,"child":2,...}` — correct child, and no duplicate-guard false positive even though Milo had wet diapers minutes before (the guard is per-child, correct). Back on Milo: her 13:10 entries appear nowhere in his timeline, and the Milo Care Dashboard kept showing Milo's last diaper. Switched to Zoe: her timeline shows her entries over seeded history with proper day headers, zero Milo leakage (`28`).

Mid-running-timer switch: I started a sleep timer as Zoe, then flipped to Milo. The server timer is stamped `child: 2` from the moment of creation, Milo's screen neither shows nor offers Zoe's timer, and saving it (from Zoe's view) landed `{"id":18,"child":2,...}`. Isolation is correct. But the other side of that coin: on Milo's screen there was **no trace that any timer was running** — no strip, no dot on the quick action, nothing. I know it's Zoe's; the app assumes I'll remember. I won't.

---

## What earned my trust
- Every single write I made survived wire inspection exactly: enums, amounts, nulls, seconds, child ids, offline replay order. I tried to catch this app lying to my server and I could not.
- PATCH-only edits with seconds preserved; `nap` never sent by the client; honest client-side validation that disables Save with a reason instead of letting the server play teacher.
- The overlap rejection flow: server error translated faithfully and *locally in time and space*, with four real resolution paths and no auto-delete.
- Offline queue: ordered, idempotent, id-adopting, and deep-linkable afterwards.
- About screen that names licenses correctly and doesn't pretend the heritage doesn't exist.

## What broke it
1. **The timeline does not believe the local database.** Every save this session — online *and* offline — failed to appear in the list until I pulled-to-refresh or force-stopped the app. Offline it's worse: my "offline one" note was invisible with no badge even after pull-to-refresh, yet I could see it sitting in the Room DB (`syncState=PENDING`) the whole time; it only showed up with its "Will sync" badge after a restart. Worst case: a sleep entry that was in the local DB **and** on the server still didn't render after pull-to-refresh — only a restart brought it. I verified the data was never lost; from a user's chair that doesn't matter, because "I logged it but can't see it" and "it didn't save" are the same experience. Jordan flagged this as her #1 last round; Sam re-flagged it; I'm confirming it with wire-level proof that the data is fine and the list is simply not listening to the database it owns.
2. **My API token is stored in plaintext in a backuppable file.** Self-hosters mint tokens; we also read breach news. I want to hand this app a key and know it can't quietly ride a cloud backup off the phone.
3. Small stuff, all fixable: "12:49 PM–12:49 PM nap" copy; Care Dashboard "ago" numbers freeze between 15-minute refreshes (16m → 21m in one hop); a running Zoe timer is invisible on Milo's screen; day-boundary fixes cost ~50 stepper taps; long-press dumps me on the server's login wall instead of offering the (correct!) link.

---

## What I want (in my order)
1. **When I hit Save, the row is on my screen — instantly, every time, online or off.** The database already has it (I checked); the list just has to listen to it. Offline rows must sit visibly with their "Will sync" badge from the moment they're created, not after a restart.
2. **Let me hand the app an API token and keep it out of backups.** Accept a pasted token at login (I mint them for scripts anyway), and no matter how I authenticate, my key must never leave the phone through Android's backup door.
3. **When any timer is running, every screen tells me whose it is.** A one-line chip — "Zoe's sleep · 12m" — on the other child's dashboard, and an accent on the quick action. Isolation of data is right; isolation of *awareness* is a forgotten-nap factory.
4. **Make the escape hatch offer "Copy link" next to opening the browser.** The URL it builds is perfect (`/notes/11/`, right id, born offline) — but I live in a browser that isn't logged in. Give me the link; I'll do the auth.
5. **Teach the edit sheet to cross midnight.** ±1m/±15m steppers are beautiful for small fixes and hopeless for "that was actually yesterday." One long-press-to-a-real-time-picker, or a −1h/−8h step, and I'm whole.
6. **Show me the rejection in the user's timezone with seconds when minutes collide** — "the 20-second nap at 12:49 PM" instead of "12:49 PM–12:49 PM". And while you're in there: "ago" on the Care Dashboard should either tick or carry a "checked 5m ago" stamp.
7. **Make About's links tappable and link this app's own source.** If we're proud of independence and licensing, let me click it.

*(Repo housekeeping I noticed while reading: `docs/qa/FEATURE-CHECKLISTS.md` calls the diaper quick action `dashboard/quick_action/DIAPER`, but the built tag is `dashboard/quick_action/CHANGE` — cost me a failed tap. Fix the doc, not the tag.)*

---

## VERDICT: **FIX FIRST**

The data path — the part I actually audit — is the most faithful I've tested in this space: wire-exact creates, honest PATCHes, correct offline replay with id adoption, and a rejection flow that respects both the server's truth and my timezone. I want this app on my server. But an offline-first app whose own list won't believe its own database is asking me to take sync on faith, and faith is exactly what I don't do. Fix the timeline liveness (it's Jordan's open #1 — my evidence narrows it: the data is always there, the UI isn't listening), lock down the token, and this flips to SHIP IT for my household.
