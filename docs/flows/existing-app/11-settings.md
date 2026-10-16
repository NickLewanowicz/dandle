# 11 — Overflow menu, Settings, Help slideshow, About, Contact developer

## Purpose
All secondary screens reachable from the dashboard's 3-dot overflow menu.

## Entry point
Toolbar → "More options" (3-dot).

## Menu items (exact labels, order)
1. `Help`
2. `Settings`
3. `Logout`
4. `Contact developer`
5. `About Baby Buddy for Android`
(No "Export debug logs" in this release build — that entry only exists in debug builds per the
research doc.)

### Settings
Single preference screen titled "Settings":
- `Dark/Light mode` — value shown "Follow system" (system / light / dark ListPreference).
Nothing else. Changing it re-themes live (not exercised further).

### Help (in-app slideshow)
Toolbar "Help: <topic>". Structure = 2-level pager: horizontal topics, vertical sections within a
topic; large hollow right-arrow (next topic) at mid-right, hollow down-arrow (next section) at
bottom-center, up-arrow appears once past the first section. NOTE: tapping the arrows did NOT
advance in this environment; vertical/horizontal SWIPES work reliably.

Complete slide inventory as rendered (5 topics, 9 slides):
1. **Help: Login**
   1. Login — screenshot of login screen with caption "There are two options for connecting to
      your Babybuddy server: … Or you choose the QR Code option (scroll down for details on that
      option)."
   2. QR Code login — "Scan QR Code" screen with QR + "Found login code for Baby Buddy website
      on: https://babybuddy-test.pkgsoftware.eu/" + `LOGIN NOW`/`CANCEL`; caption explains the
      web-UI "Add a device" page.
   3. Insecure Login — http-warning dialog image; caption "… Only use this feature for local
      BabyBuddy servers in a network that you control!" (image shows OLDER dialog wording than
      the live app — see 99-discrepancies).
   (No "Home Assistant Ingress" slide exists in this build.)
2. **Help: Overview** — 1 slide: dashboard screenshot; caption about buttons on top, history
   below, horizontal swipe to switch children.
3. **Help: Using Timers**
   1. red arrows pointing at the four timer buttons; "To start a timer, press any of the
      timer-event buttons…"
   2. feeding panel image; "Starting a time will open an interface related to the timer… Once all
      required fields are entered a save-button will appear."
   3. panel with red-boxed save button; "Once the save-field appears, the timer can be stopped by
      pressing the save-button. You can also click the timer-button top-row again to cancel a
      timer instead of saving it." (image shows the OLD save-button position, bottom-left.)
4. **Help: Logging Diapers and Notes** — 1 slide: diaper panel image; caption "In addition to
   timer events the top-row allows logging of non-timer events… but without a timer."
5. **Help: Event history** — 1 slide: timeline image; caption "…edit entries by pressing the
   entry in the history for three seconds. This will open the corresponding page in the BabyBuddy
   app. To delete an entry, press the red "delete" button."

### About Baby Buddy for Android
Static text page: "This Android application is Open Source software created by Paul Konstantin
Gerke and licensed under the MIT License (see below)." + source link
https://github.com/babybuddy/babybuddy-for-android/ + full MIT license text + "Third party
libraries" list (kotlinx, …).

### Contact developer
Explains messages go to the maintainer ("no way for me to respond… refer to GitHub instead"),
message text field, buttons `SEND` and `BACK`.

## Screenshots (docs/screenshots/existing-app/11-settings/)
- `01-overflow-menu.png` — 5 menu items
- `02-help-1.png` … `10-help-9-history.png` — all 9 help slides in order
  (`05-help-4-overview.png` is the Overview topic; files 05–10 cover topics 2–5)
- `11-settings-screen.png` — Settings with single Dark/Light mode pref
- `12-contact-developer.png` — Contact developer screen
- `14-about.png` — About page (license + third-party libraries)

## Replication notes for the new app (MUST match)
1. Menu = exactly those five entries (debug log entry absent in release).
2. Settings has (at least) the single Dark/Light mode preference with "Follow system" default.
3. Help slideshow: 5 topics / 9 slides with the captions quoted above (these texts define the
   intended UX and should be mirrored — with corrected screenshots — in the new app's help).
4. About page: MIT text + GitHub link + third-party attribution list.
5. Contact developer: note that replies are impossible; SEND/BACK.
