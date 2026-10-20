---
title: "Persona feedback: Jordan"
---

# Jordan — beta feedback, round 1 (2026-10-17)

**Who I am:** parent of a 4-month-old. I log 10–20 times a day, one-handed, usually with a baby on my other arm, often at 3 a.m. from memory. If the app makes me think, I make mistakes. If the app lies to me once, I stop trusting it.

**What I tested:** the built app on the emulator, logged in against the real dev server, dark theme, both home-screen widgets in place. Everything below is what I actually saw and did (evidence under `docs/screenshots/ux-review/jordan/`).

---

## My morning with it

### 1. The 3 a.m. feed (started 20 min ago, I never started a timer)
I open the app and tap **Feeding**. A timer starts instantly at `00:00:00` and the panel slides up — Type / Method / Amount, my defaults remembered (`03`, `04`). Here's my problem: the feed started twenty minutes ago and the app has decided it started *now*. There is nowhere in the panel to say "no, earlier". So I do what any tired parent does: fill in Formula and 120 mL, hit Save, and plan to fix the time after.

Except — when the keyboard is open for the amount, **Save is buried under the keyboard edge**. My tap lands in dead space (`05`, `06`). I press BACK to dismiss the keyboard, and my next tap somehow **starts a Pumping timer I never asked for** (`06`) — a stray tap in the shifted layout landed on a quick action. To the app's credit, the stop guard caught it ("Stop timer? You've been tracking Pumping for 2:57", Discard red, Save green — `07`), and my Formula/120 choices survived the whole mess (`08`). Final count for one bottle: **about 10 interactions, one accidental timer, one lost tap**. The result in the timeline says `10:52` — the moment I tapped the button, not the moment she ate.

### 2. One-handed diaper at the changing table
This is the best flow in the app. Tap **Diaper**, two giant Wet/Solid buttons, Save is dead until I pick one (can't save an empty change — good). Wet → Save: **3 taps**, huge targets, no typing. With solid + extra options the color dots on the chips are genuinely readable in the dark (`28`), and with extras open the Save button actually moves *down* into thumb range (`28`). One gripe: with the panel compact, Wet and Solid look nearly identical when both are on (`28`) — at 3 a.m. I can't tell at a glance what I already toggled.

### 3. I fat-fingered the time (25 min early)
Tap the wrong row? No — tap the right row, **Bottle · 120 mL**, and the quick edit sheet opens (`23`): Amount, Type, Method, START and END each with −15m / −1m / +1m / +15m. Moving start shifts end with it and **the duration survives** — 10:52→11:00 became 10:27→10:35, still 7 minutes (`25`), and the server got a PATCH, not a PUT. That part is exactly right.

But 25 minutes = −15m once **plus −1m ten times**. Eleven taps on little buttons. For "the diaper was actually 25 minutes earlier" — an extremely common real-world mistake — the app gives me no coarser way. Total fix: **13 interactions**.

### 4. My partner already logged a diaper 2 minutes ago
I save a second wet diaper a couple minutes after the first and the app stops me: **"Already logged? Wet diaper logged 2 min ago. Add anyway?"** with *View last entry* and *Add* (`29`). The wording is calm and specific — no red, no alarm, it tells me exactly what and when. Genuinely good.

My worry is the buttons: **"Add" is the big filled green one**. Half-asleep me always taps the filled button. If the duplicate is the accident (it usually is at our house), the guard gets bypassed by muscle memory exactly when I need it. It also said "Wet diaper" when I was logging a wet+solid — close enough to be useful, but it read only part of my entry.

### 5. The dark-room test
Dashboard, panels, stop dialog, edit sheet, duplicate dialog — I photographed all of them (`01`–`34`). Nothing blinds me, nothing disappears: labels, chips, color dots, red-vs-green buttons all legible. This pass is clean. No notes.

### 6. The park with no signal
Airplane mode on. The app notices — an **Offline** chip appears (`30`) — and keeps working: I log a note and run a 4-minute sleep timer, both fully offline. What tells me it worked? **Almost nothing.** The panel closes and that's it — no new row anywhere, no "Will sync" badge, no quiet "saved" acknowledgment (`31`, `33`, `35`). Signal comes back: the Offline chip clears (`36`), and the server received both entries, in the order I made them — but the app itself never *showed* me any of that. I only know it worked because someone read the server logs for me. From my chair, the honest answer to "do you trust it?" is **no — not because sync failed, but because sync is invisible**. My real-world behavior would be to log everything twice "just in case".

### 7. "When did she last eat?"
- **From the home screen:** glance at the Care dashboard widget: **"Last feed — 59m · 120 mL"** (`38`). Two seconds, zero taps, no math, amount included. This is the single best thing in the app for me.
- **From inside the app:** the timeline says **"Bottle · 120 mL — 10:27"** (`39`). Now *I* have to compute 11:38 − 10:27. I can do it; at 3 a.m. I'd rather not, and sometimes I'd get it wrong. The app makes me do math the widget already knows the answer to.

### 8. The curious thumb
- Tapping a row opens quick edit — nice, but it means *every* list row is one accidental tap away from an editor. A couple of my scrolling gestures opened edit sheets I never asked for (`15`).
- Long-press: at ~1 second, nothing happens. At ~2 seconds, **Chrome opens on the Baby Buddy web UI** (`41`) — except it lands on the **login page**. I just spent my password on this app; now the web wants it again, and the "more fields" escape hatch turns into a password wall in the dark.
- The ✕ on a running timer opens the stop guard rather than silently killing data (`07`) — good.
- The sync chip only exists when there's something to say. Fine idea, but combined with #6 it means most of the time sync is completely silent.

### The thing that scared me
All morning, **every entry I saved was missing from the timeline until I fully closed and reopened the app.** The bottle from #1 saved at 10:59 never showed up while the app was open (`12`); the two diapers from #2 (`27`), the offline note and nap from #6 (`31`, `35`) — all invisible. The list even got into a state where scrolling just stopped at yesterday evening (`17`–`20`). After I force-stopped and reopened, everything was there, correctly ordered (`43`) — **the data was never lost, the display just refused to show it.** For a parent, "I logged it but I can't see it" is indistinguishable from "it didn't save". This is the one thing that would send me back to paper.

---

## VERDICT: **FIX FIRST**

The bones are right: the quick actions are fast, the widgets are genuinely great, the dark theme is clean, offline queuing actually works, and the edit sheet's duration-preserving time nudge is exactly the interaction I want. But the app currently fails its core promise — *what I log appears* — and hides its own reliability from me. I won't trust it until I can see it working.

## What I want (in my order)

1. **When I hit Save, the entry appears at the top of Today. Instantly. Every time.** I don't want to wonder whether my diaper got logged; I want to see it. (Evidence: `12`, `27`, `31`, `35` vs `43` — everything showed up only after a full restart.)
2. **When I open a quick action, let me tell it "this actually started 20 minutes ago" right there** — one obvious control on the panel itself. Today the feed saves with the wrong time and the honest fix costs me 13 taps (`03`–`08`, `23`–`25`).
3. **When we're offline, show me the story:** my new rows visibly sitting there with a "Will sync" badge, and a visible moment — chip, banner, anything — that says "all caught up" when they land. Right now the only signal is a small chip that disappears (`30`, `36`); that's not trust, that's silence.
4. **Answer "how long ago?" in the app, not just the widget.** The widget's "59m · 120 mL" is the perfect answer (`38`); the app's "10:27" makes me do arithmetic at 3 a.m. (`39`). Show me both.
5. **Give me bigger jumps for fixing times.** −15m/−1m is beautiful for a 15-minute mistake; for a 25-minute one it's eleven taps (`25`). I want a −30m/−5m (or press-and-hold that accelerates), or a plain time picker.
6. **The long-press escape hatch should take me to the entry, already logged in** — and tell me it exists. Landing on the web login page after 2 seconds of holding (`41`) is a dead end in the dark; and 1 second of holding gives no hint anything is coming.
7. **Keep the duplicate guard's words, reconsider its emphasis.** "Wet diaper logged 2 min ago. Add anyway?" is perfect (`29`). But make the calm path — *View last entry* — the button my thumb naturally hits, so a sleepy tap doesn't always mean "add the duplicate".
8. **Never hide Save behind the keyboard, and don't let BACK eat my panel.** Logging a bottle turned into an accidental Pumping timer because Save vanished under the keyboard and my recovery taps landed on quick actions (`05`–`08`).
9. Small stuff: Wet and Solid should look clearly different when both are on (`28`); the widget's "Last nap 0m" should say "just now" (`38`); one save button rendered as an empty pill (`08`).

---

*Session evidence: `docs/screenshots/ux-review/jordan/01…43-*.png`. State restored: app on dashboard, no running timers, widgets in place. Events I added this session (left in the demo data): 1 feeding (edited to 10:27), 2 diaper changes (11:22, 11:25), 1 nap (11:29–11:33), 1 note ("park", 11:28; my typing tool truncated the text — the app is not at fault).*
