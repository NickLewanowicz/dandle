# 12 — Edge behaviors (logout, bad server, background/resume, delete)

## Purpose
Error states and lifecycle behaviors observed outside the happy paths.

## A. Logout
- Overflow menu → `Logout`: **immediate**, no confirmation dialog. Returns to the login screen
  with the server URL prefilled and username/password cleared. The "help" tutorial bubble shows
  again. The selected child also survives (next login opens the last selected child's page).
- The API token remains valid server-side (verified by continuing to curl with it).

## B. Dead server URL at login
- Enter `http://10.0.2.2:9` (closed port) + admin/admin → LOGIN → http-warning → CONTINUE ANYWAY
  → ~5-10 s wait → dialog: "Login failed" / "Failed to connect to /10.0.2.2:9" (raw exception
  text, no friendly mapping), button `OK`. Fields keep values. Restoring the URL and logging in
  again works normally.

## C. App backgrounded mid-timer, then resumed
- Started a sleep timer on Zoe's page (server timer id=8, child=2, started 21:46:19Z), pressed
  HOME, waited ~8 s, relaunched the app: dashboard reopened on Zoe with the sleep panel already
  open and the counter at 0:01:09 — correct elapsed time computed from the server timer.
  (Also verified across a full process kill earlier: 0:01:52.) The timer is entirely
  server-side; nothing timer-related is persisted on the device.

## D. Delete entry (also covered in 09-history.md)
- Trash button → "Delete entry" confirm dialog → DELETE → row removed, server count decremented.

## E. Environment notes (not app behavior)
- Another app (`net.babybuddy.next`, the new template app) was installed and auto-launched on the
  shared emulator mid-session (17:08), stealing foreground twice; this is documented in
  `03-sleep/04-interrupted-by-other-app.png` and had no effect on server state.
- The live timer counter's ~500 ms animation prevents `uiautomator` from reaching idle; element
  dumps return stale content while any timer panel is open. Interaction must then rely on
  screenshots + fixed coordinates. Relevant for future UI test automation of both apps.

## Server effect
- Logout: no request. Bad-URL login attempt: connection attempt fails, no state change.
- Background/resume: polling resumes; no duplicate timers (the existing one is re-used).

## Screenshots (docs/screenshots/existing-app/12-edge/)
- `01-after-logout.png` — login screen after logout (URL prefilled, tutorial bubble back)
- `02-dead-server-error.png` — "Login failed / Failed to connect to /10.0.2.2:9"
- `03-relogin-success.png` — reopened on Zoe after re-login (child selection persisted)
- `04-timer-survives-background.png` — sleep panel with 0:01:09 after HOME + relaunch

## Replication notes for the new app (MUST match)
1. Logout: no confirm; URL prefilled afterwards.
2. Connection errors surface the raw exception message in a "Login failed" dialog.
3. Timer continuity across background AND process death (server-authoritative elapsed time).
4. Do not lose the selected child or panel drafts across restarts.
