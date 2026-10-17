# Sam — beta feedback, round 1 (2026-10-17)

**Who I am:** the other parent. I did not choose this app and I do not love it. My phone lives face-down on the counter; I open this thing maybe twice a day, usually because someone is asking me a question ("has she been fed?") or because a diaper happened on my watch. What I want from it is answers and one-tap actions **from the lock/home screen** — the app itself is what I tolerate, not what I enjoy.

**What I tested:** the built app on the emulator, logged in against the real server, both home-screen widgets in place (Quick actions 2×2: Sleep / Bottle / Diaper / Pump; Care dashboard for Milo). Everything below is what I actually saw and did — evidence under `docs/screenshots/ux-review/sam/`.

---

## My day with it

### 1. Morning nap, from the home screen
Baby's down. I look at my home screen: the dark 2×2 grid is already there. I tap **Sleep**. **One tap. The app never opened.** The Sleep cell flipped to a filled blue chip reading `0m`, and the dashboard below grew a row — "Running: Sleep · 0m — tap to stop" (`00`, `01`). I checked with the helper: the server got the timer (`POST /api/timers/ → 201`). This is the entire reason I would keep this app. I have never once successfully logged a nap on the old app; I usually forget the app exists until the nap is over.

### 2. "When did she last eat?"
Forty minutes later, still not opening anything. The dashboard answers in one glance: **"Last feed — 1h · 120 mL"** (`04`). Under three seconds, no math, amount included. Same as the other parent found: this row is the best thing in the app.

But — **whose data am I reading?** There are two kids. The name "Milo" is in the corner in small type (`04`), and if the question is about *Zoe*, this widget cannot answer it. The home screen speaks fluent Milo and says nothing about Zoe unless I open the app or build a second widget and find screen space for it.

### 3. A diaper happened
Tapped **Diaper** on the widget. The app opened (unavoidable — a diaper needs a wet/solid answer, and the widget honestly can't guess) but it opened **straight onto the diaper form, correct kid preselected**: two giant Wet / Solid buttons and Save (`02`). Wet → Save. **Three taps from the home screen to saved**, and the server logged it (`POST /api/changes/ → 201`).

Then the trust wobble: I saved it and **the timeline still showed 11:29 as the newest entry, five minutes later** (`03`). The server had it. The app didn't show it — until I happened to scroll the list, and my "Wet · 11:50" finally appeared (`03b`). The other parent flagged this exact thing in their round and I'm flagging it again: "I logged it but I can't see it" and "it didn't save" are the same experience from my chair. (For what it's worth, the *widget* updated instantly — "Last diaper 3m · Wet" — so the data was clearly fine; it's the list that sulked.)

### 4. Grandma texts "has she been fed?"
Dashboard read-aloud: Last feed **1h · 120 mL**; Last diaper 26m · Wet; Last nap 9m · 19m; Last pump 12h; Running: Sleep 5m — tap to stop (`04`). The feed row makes sense as a sentence and I can repeat it on the phone to grandma verbatim. "Last nap **9m · 19m**" I had to think about — two bare numbers that mean "9 minutes ago · lasted 19 minutes", and the dot doesn't tell you which is which. The feed row gets away with it because mL can only be a length; minutes can be either.

"**Running: Sleep — tap to stop**" sitting permanently on my home screen does scare me a little: this is the row my pocket can reach. See #6 for what actually happens.

### 5. I dare to open the app
App drawer first: there are **three** look-alike baby apps in there — "Baby Buddy" (the old one), "Baby Buddy…" (ours, name truncated into a twin of the old one), and "BuddyTrack" (`05`). I hovered over the wrong one before spotting the moon-and-bottle icon. Inside, the dashboard is quick actions then the Today list (`06`). Where do I look for "last feed"? Nowhere specific — I scan the timeline for the newest Bottle row (10:27 · 120 mL) and do the "how long ago is that" math myself. **The widget answers faster than the app does**, because the widget shows the summary and the app only shows the raw list.

### 6. The curious thumb
- **Tapped the widget's "Running: Sleep — tap to stop" row:** it opened the app with the timer still running (`19`). It did *not* stop. The label promises something it doesn't do. In the app, tapping ✕ brought up the guard — "**Stop timer? You've been tracking Sleep for 1:32.** Discard / Keep / Save" (`20b`) — which is exactly the safety net I want between my pocket and a lost nap. So: right mechanics, wrong label on the widget.
- **Long-pressed the widget:** resize handles plus a green pencil (`21`). Pencil is a guess-but-correct icon for "edit this widget".
- **Widget configuration:** plain language, no jargon — "**Sleep starts/stops the sleep timer; the others open the app.**" — then Child (Milo / Zoe) and Button 1–4 pickers (`22`). I understood every option in about five seconds, and it finally explained why Diaper opens the app. Nothing I'd change except… see asks.

### 7. Something is "failed"
No failure was seeded in my session, so I made an honest one the way a real parent would: I saved my own nap, then edited its start back until it crashed into the earlier nap. The server refused it (`PATCH → 400`). What I got:

- a red **"1 failed"** chip appears at the top of the app (`13`);
- the failed row wears a **"Sync failed"** badge right in the timeline (`14`) — nothing hidden, nothing vanished;
- tapping the row (or the chip, which jumps me straight to it) opens a dialog: "**Couldn't sync this entry — This overlaps the 11:29 AM–11:33 AM nap.**" with Edit / Show entry / Retry / Delete locally (`16`).

That sentence is in parent. It names the exact nap I collided with. I tapped Edit, nudged the start +15m twice (a "can't be in the future" check caught one fumble), Save — server took it (`PATCH → 200`), badge and chip cleared, the nap sits in the list like nothing happened (`17`). **I fixed a sync conflict without understanding a single technical word. That never happens.**

One leak: the edit sheet itself showed the raw server line — "Another entry intersects the specified time period. Conflicting entry: Sleep (10/17/2026 **3:29 p.m.** …)" — robot voice, and the time is wrong by my clock (it was 11:29 at my house). The dialog translates; the sheet forgets to.

---

## VERDICT: **FIX FIRST** — but it's close, and it's a short list

The widget life is genuinely built for me: one-tap nap timers, glanceable answers, honest configuration, and a failure path I could actually follow. What keeps it at FIX FIRST is one trust bug the other parent already flagged that I hit again, plus the fact that my home screen only knows about one of my two children. Fix those and I ship it — the app itself I'll keep tolerating, which is the correct amount.

## What I want (in my order)

1. **When I hit Save, the entry shows up at the top of Today. Instantly. Without me scrolling.** I logged a diaper at 11:50 and the list kept telling me the newest thing was 11:29 for five minutes (`03` vs `03b`). The widget knew; the list didn't. I will not trust a tracker that hides the thing I just logged.
2. **The home screen should answer for both kids.** I don't know at a glance whose "1h · 120 mL" I'm reading, and for Zoe there is no answer on the home screen at all (`01`, `04`). Make the name impossible to miss, or let one dashboard show both kids — I don't have home-screen real estate for two widgets.
3. **The widget row shouldn't say "tap to stop" if tapping opens the app.** Say "tap" or actually stop (the Discard/Keep/Save guard makes stopping safe — `20b`). A label that lies once is how I end up ignoring the whole widget.
4. **Keep the failure wording human everywhere, not just in the dialog.** The dialog's "This overlaps the 11:29 AM–11:33 AM nap" is perfect (`16`); the edit sheet's raw "Another entry intersects… Conflicting entry: Sleep (10/17/2026 3:29 p.m.)" is robot-speak with a wrong time zone. Same entry, same screen, please.
5. **Label the two-number rows.** "Last nap 9m · 19m" made me think; "9m ago · lasted 19m" (or icons) wouldn't have (`04`).
6. Small stuff: scrolling the timeline kept opening edit sheets on old entries — a stray thumb shouldn't be one tap from a Delete button; the app-drawer name truncating to "Baby Buddy…" makes it a twin of the old app — I need to tell them apart at a glance (`05`); "Last feed 1h" when it's nearly two hours is honest enough but "1h½" would be kinder.

---

*Session evidence: `docs/screenshots/ux-review/sam/00…23-*.png`. State restored: home screen, both widgets placed, no running timers, "1 failed" chip cleared. Events I added this session (left in the demo data): 1 wet diaper (11:50), 1 nap (moved to 11:47–12:07 while testing the failure path); the second test timer was discarded and never saved.*
