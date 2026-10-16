# 01 — Login / connection setup

## Purpose
Connect the app to a Baby Buddy server with server URL + username + password (or QR code — not
exercised here). The app logs into the web UI, scrapes the API token, then uses token auth.

## Entry point
App launch (cold) when no stored credentials, or overflow-menu → Logout.

## Step-by-step flow (exact UI labels)
1. Screen "Login to Baby Buddy" (toolbar) shows:
   - Intro text: "Please enter the URL to your Baby Buddy WebApp installation. If you do not have
     a Baby Buddy server already, please visit the project page to find instructions how to host a
     server yourself." ("Baby Buddy WebApp" is a teal link.)
   - `Server Address` (EditText, hint), `Login Name`, `Login Password` (masked dots)
   - `LOGIN` button — **disabled until all three fields are non-empty** (verified in UI tree:
     enabled=false → true)
   - Caption "You can also try to login using a Baby Buddy login QR code:" + button
     `LOGIN WITH QR CODE`
   - 3-dot overflow menu exists here too (only Help/About-type entries relevant).
   - First-run tutorial bubble (green, pulsing — this animation keeps uiautomator from ever
     reaching idle): "To get started, check out the help in the main menu." with a green arrow
     pointing at the 3-dot menu. Dismissed by tapping the bubble itself (a tap on empty space does
     nothing). It re-appears after logout.
2. Enter URL `http://10.0.2.2:8000`, name `admin`, password.
3. Tap `LOGIN` → **"Insecure connection" dialog appears BEFORE any network attempt**:
   - Title "Insecure connection"
   - Body: "This server URL does not start with 'https'. This means that the password you entered
     can be intercepted and stolen!"
   - Buttons: `CANCEL (ADVISED)` | `CONTINUE ANYWAY` (shown on every http login attempt; not
     remembered).
4. `CONTINUE ANYWAY` → (correct credentials) → dashboard. Login is fast (~2-4 s).

## Branches / states
- **Wrong password**: after CONTINUE ANYWAY → dialog "Login failed" with the **server's own error
  text** prefixed by ": " — ": Please enter a correct username and password. Note that both fields
  may be case-sensitive." Button `OK`. Fields keep their values.
- **Unreachable server** (bad URL/port): dialog "Login failed" with raw exception message:
  "Failed to connect to /10.0.2.2:9". Button `OK`.
- **Logout**: overflow menu → `Logout` — immediate, no confirmation. Returns to login screen with
  the **server URL prefilled**, username/password cleared. The "help" tutorial bubble shows again.

## Server effect
Password login performs the web-UI login handshake and token scrape (`GET login/` → `POST login/`
→ `GET api/profile` for the api_key). After that the app authenticates API calls with
`Authorization: Token <key>` (verified independently with curl using the same token).

## Screenshots (docs/screenshots/existing-app/01-login/)
- `01-initial.png` — login screen with pulsing help tutorial bubble
- `02-tutorial-bubble.png` — same, zoomed state after first dismiss attempt
- `03-fields-filled-wrong-pw.png` — filled form, LOGIN enabled
- `04-wrong-password-error.png` — "Insecure connection" dialog (fires before credentials are checked)
- `05-invalid-credentials-error.png` — "Login failed" with server error text (leading ": ")
- `06-http-warning.png` — same insecure dialog on the second attempt
- `../12-edge/01-after-logout.png` — login screen after logout (URL prefilled, tutorial again)
- `../12-edge/02-dead-server-error.png` — "Failed to connect to /10.0.2.2:9"

## Replication notes for the new app (MUST match)
1. Three fields + disabled-until-complete LOGIN button; password masked.
2. http-warning dialog shown BEFORE the request, with `CANCEL (ADVISED)` / `CONTINUE ANYWAY`,
   re-shown on every attempt (text is version-dependent — the in-app help image shows older
   wording; live app wording is authoritative, see above).
3. Errors shown in a "Login failed" dialog: server alert pill text for bad credentials (including
   the quirky leading ": "), raw connectivity exception for unreachable hosts.
4. QR login entry point: caption + `LOGIN WITH QR CODE` button (payload format
   `BABYBUDDY-LOGIN:{json}` per research doc; not exercised here).
5. Logout is instant (no dialog) and preserves the server URL in the field; selected child also
   survives logout (see 12-edge).
6. HELP tutorial bubble on first login screen ("check out the help in the main menu"), dismiss by
   tapping the bubble.
