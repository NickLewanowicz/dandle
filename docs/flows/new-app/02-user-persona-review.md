# 02 — User Persona Review ("Jordan") — spec v0.1 BETA

Reviewed from the user's chair against `00-functional-spec.md`. I have a 4-month-old, a partner on another phone logging the same kid, spotty Wi-Fi, and about one free hand at any given moment. I open this app 10–20 times a day, mostly to answer "when did she last eat?" or to make the diaper counter go up.

## The moments I tested against

**3 a.m. feeding.** She's done eating, I look at the phone, I never started the timer. This is my real life: logging happens *after*, from memory, wrong by 15–30 minutes. So the spec's Quick Edit isn't a nice-to-have to me — it's where most of my actual logging lives. Which is why the time-editing ergonomics in §5 are the make-or-break part of this whole document.

**One-handed diaper at the changing table.** Open app, tap Diaper, tap Wet, tap Save. Three taps, no reading, thumb-sized targets. The Save-until-a-toggle-is-on rule is good — that's exactly the 3 a.m. "I tapped save on an empty panel" state. Color and amount buried under "Extra options" is right; my kid stopped producing notable colors around week two and I never want to see that picker again unless I go looking.

**Park, no signal.** I save a diaper with zero bars. The row shows up immediately with a muted "Will sync" clock and I move on with my life. That badge design is the calmest, most trust-building thing in the spec. I see my entry exists, nothing is red, nothing is spinning at me. Keep it exactly as written.

## 1. The six quick actions

Order is right: Feeding, Diaper, Sleep are my entire day and they're the first three buttons. Notes I use monthly; fine as button four... wait, five. Fine either way, it's parity.

- **Feeding defaults are wrong for us.** Hardcoded type=breast milk + method=bottle means we re-select *formula* at something like 8 feeds a day, every day, forever. That's the single most repeated annoyance this spec bakes in. The app should remember what we logged last (per kid) and default to that. For a pumping household the current default is great — for us it's a daily tax.
- **The stop-timer dialog asks me a question I answer the same way every time.** Every real nap end: Stop → read three buttons → Save. The <10s auto-discard already catches my accidental pocket-taps, so the dialog is mostly friction. If it stays, Save needs to be the visually obvious answer, not one of three equal options I have to read in the dark.
- **No way to log something that already happened.** Start-timer-then-edit is the only create path for timed types. At 3 a.m. I start the timer halfway through the feed, save, then fix the start. It works, but the "after the fact" case is my *primary* case and it's currently a two-step dance.

## 2. Quick Edit (§5)

The design goal says "fix a time in ≤3 interactions." My most common fix: "feed actually started 20 minutes earlier." With ±5m steppers that's four presses plus Save. **The promise breaks at exactly the interval I live at.** I don't think in 1-minute units; I think in 5s and 15s. Give me a 15-minute nudge (or a time I can drag) and 20–30 minutes becomes two taps and the promise is real.

- **Fat-finger zone:** four small steppers in a row (−5/−1/+1/+5) at the bottom of a sheet, one-handed, dark room. −1 and +1 are neighbors; one wrong press silently walks the time the wrong way and costs me two more taps. Save-disabled-with-a-reason is genuinely good — I can't poison the data, I just burn patience.
- **The spec is silent on the question that matters most:** when I move a feed's start, does the end move with it? If not, "started 20 min later" produces an invalid entry and a dead Save until I fix a second clock. The *duration* is the thing I actually remember. When I move the start, the length should survive.
- Diaper edits (toggle Wet+Solid, fix a time) are comfortably within three interactions. Delete-with-confirm inside the sheet is the right amount of friction for a destructive act.

## 3. Offline & sync (§6)

- **PENDING is perfect.** Quiet icon, "Will sync," entry visible instantly. I never have to wonder.
- **FAILED is the right shape** — amber outline, tap for the reason, Edit/Delete/Retry, never auto-delete, never blocks other entries. That last pair of MUSTs is the most trust-building language in the document. But the reason shown is server-speak: "Another entry intersects the specified time period" asks me to translate database into English with one hand. Say "This overlaps the 1:15–2:00 nap" and let me jump to that entry. Give me the *clash*, not the *constraint*.
- **"Session expired" mid-sync is a jump-scare** unless the message also says "your 3 unsent entries are safe — sign in and they'll go up." Otherwise I'm typing my password at 3 a.m. with my pulse up, convinced the nap log is gone.
- **Near-duplicate diapers (scenario 5) will be our Tuesday.** The accept-and-delete-later tradeoff is survivable (delete is 3 taps), but the cheap fix is at save time: one calm line — "Wet diaper logged 4 min ago. Add anyway?" — defaulting to Add. One tap either way, caught while I still remember which log was the real one.

## 4. Widgets (§7)

- **The Care Dashboard is the killer feature.** "Wait, when did she last eat?" answered on the lock screen without opening anything is worth the whole spec. Last feed and last diaper are the rows I'd actually read; last nap yes; running-timer row yes. Minute-granular staleness is fine — honestly preferred — as long as tapping lands me in the app where truth lives.
- **Quick Actions widget:** the Sleep toggle from the home screen is the one I'd hit a dozen times a day — baby's down, one tap, phone back in the pocket. Bottle, useful. But **Pump takes a permanent quarter of the widget** and I have never pumped. §7.3 lets me configure dashboard rows and child, but not the four buttons. Let me pick them.
- Nothing here is gimmicky. I especially like the honesty of "diaper opens the app with the panel open" instead of fake one-tap logging that saves a half-empty entry.

## 5. Not my problem / missing

- **Don't have:** diaper color as anything but a buried extra (it is — good). Pumping as a *prominent* surface (it needs to exist, not to lead). The long-press-to-web — I'll never discover it, it costs me nothing, keep it for the power user in this house (my partner).
- **Missing daily:** after-the-fact logging as a first-class move; defaults that learn; the duplicate nudge.
- **Thank you for:** no dashboard, no insights, no social, no notification nags. §8's "nothing decorative that delays logging" and §10's cold-start-under-2s are the two lines I care about most in the whole document. The restraint is a feature.

## VERDICT: FIX FIRST

A short list. The offline model, the badges, the widgets, and the general restraint are ship-ready as written — this spec clearly knows who it's for. What's wrong is concentrated in the exact flows I use most.

1. **Make "started 20–30 min earlier" a two-tap fix** — 15-minute nudges or a draggable time — and keep the duration when I move a start time. Never make me fix two clocks or hit a dead Save.
2. **Defaults that learn:** feeding type/method should remember our last choice per child, not hardcode breast milk + bottle. We re-pick formula eight times a day.
3. **Duplicate guard at save:** "Wet diaper logged 4 min ago — add anyway?" as one calm line, defaulting to Add.
4. **FAILED and Session-expired in parent language:** name the clashing entry ("overlaps the 1:15–2:00 nap") and let me jump to it; always pair "Session expired" with "your N unsent entries are safe."
5. **Let me choose the four Quick Actions widget buttons** and their order; Pump shouldn't own a permanent quarter of my home screen.
6. **On the stop-timer dialog, make Save visually the answer** — three equal buttons in the dark is a reading test.
