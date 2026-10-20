---
title: "Existing app architecture"
---

# Baby Buddy for Android — Architecture & Scope Study

Studied from source at commit of `main` (October 2026). Local checkout used for this study:
`.scratch/babybuddy-for-android/` (all paths below are relative to that repo root).
App version studied: **2.6.4** (versionCode 54). MIT-licensed code; media has separate licenses (see §8).
README states development is "temporarily on hold" (expected to resume "in the Winter 2025" — i.e. it has been stagnant).

---

## 1. Tech stack

| Aspect | Value | Source |
|---|---|---|
| UI framework | **Native Android XML Views** (NOT Compose, NOT Flutter) with ViewBinding | `app/build.gradle` (`buildFeatures { viewBinding true }`) |
| Languages | Mixed **Java + Kotlin** (older code Java, newer Kotlin/coroutines) | e.g. `networking/BabyBuddyClient.java` vs `networking/babybuddy/Client.kt` |
| Package / appId | **`eu.pkgsoftware.babybuddywidgets`** (namespace + applicationId) | `app/build.gradle` |
| min / target / compile SDK | **29 / 37 / 37** (Android 10+) | `app/build.gradle` |
| App label | "Baby Buddy" | `app/src/main/res/values/strings.xml` |
| Networking | Two layers: legacy `HttpURLConnection` + org.json (v1) and **Retrofit 3.0.0 + OkHttp + Jackson** (v2). OkHttp is a transitive Retrofit dep (no explicit `okhttp3` version) | `app/build.gradle`, both client files |
| Serialization | Jackson (`jackson-module-kotlin`, custom serializers), org.json (legacy) | `JacksonSerializers.kt`, `Constants.kt` |
| QR scanning | **CameraX 1.5.2** (ImageAnalysis + Preview) + **zxing-cpp** (git submodule, patched gradle file `3rdparty/patched-zxing-build.gradle`) | `login/QRCode.kt`, `build.gradle` |
| UI libs | Material Components 1.13.0, ConstraintLayout 2.2.1, Navigation-Fragment/UI-KTX 2.9.6, ViewPager2 (via legacy-support-v4), Preference 1.2.1 | `app/build.gradle` |
| Async | Kotlin coroutines (1.10.2) + hand-rolled `Promise<A,B>` / `AsyncPromise` helpers | `utils/Promise.java`, `utils/AsyncPromise.kt` |
| Text templating | `com.squareup.phrase` for `{placeholder}` strings | everywhere, e.g. `TimelineEntry.kt` |
| Kotlin / AGP | Kotlin 2.2.0, AGP 8.13.1, JDK 17 for toolchain (compileOptions Java 21) | `build.gradle`, `app/build.gradle` |

### Build pipeline (unusual — important if we copy the repo layout)
`app/build.gradle` hooks three `preBuild` tasks that require **bash, GNU make, curl, jq, ImageMagick, pandoc ≥ 3.1.1, Python ≥ 3.8 + pipenv + cython** (checked by `check-build-prerequisites.sh`):
1. `generateAboutStrings` — parses `ATTRIBUTIONS.md` + `LICENSE.md` into `res/values/about_strings.xml` (about screen content).
2. `resources.makefile` — converts `resources/*.svg` to drawables.
3. `help.makefile` — converts `resources/help_images/help{,-nl,-de-rDE}.md` into `res/values*/help_strings.xml` via `process_markdown.py` (pandoc/panflute), and copies/crops help screenshots into `res/drawable` (crop: top 170px and bottom 135px removed).

Gradle build: `./gradlew build -PskipIntegrationTests` (integration tests `ClientV2IntegrationTest` run against a real server + emulator). CI: `.github/workflows/ci.yml`.

### Architecture shape
- Single `Activity` (`MainActivity`) + **Jetpack Navigation** graph (`res/navigation/nav_graph.xml`), all screens are `BaseFragment` subclasses.
- Per-child dashboard pages in a horizontal **ViewPager2** (`LoggedInFragment` → `BabyPagerAdapter` → `BabyLayoutHolder`, layout `res/layout/baby_manager.xml`).
- Two networking clients coexist: `BabyBuddyClient` (v1, Java, HttpURLConnection; owns timers + conflict queries) and `networking/babybuddy.Client` (v2, Kotlin, Retrofit; owns list/create/delete of time entries). `BabyBuddyClient` constructs the v2 client as `v2client` (`BabyBuddyClient.java:475`).

---

## 2. How it talks to Baby Buddy

### 2.1 Auth model
- The user never pastes an API key manually in the password flow — the app **logs into the web UI and scrapes the API key** (§3). The stored key is then used as DRF token auth.
- Every request carries two headers (`AuthInterceptor`, `networking/babybuddy/Client.kt:52-64`; also `BabyBuddyClient.doQuery`):
  - `Authorization: Token <api_key>`
  - `Cookie: sessionid=...` (and any other cookies captured at login — used for Home Assistant ingress)
- Retrofit base URL: `serverUrl.trimEnd('/') + "/api/"` (`Client.kt:116`). V1 client builds `api/...` paths manually.
- Credentials live in `CredStore` (§3.3), not SharedPreferences.

### 2.2 Endpoints used (v2 Retrofit interface — `networking/babybuddy/models/ApiInterface.kt`)

| Purpose | Method + path |
|---|---|
| Profile (api_key, timezone) | `GET profile` (no trailing slash!) |
| Children (paginated) | `GET children/?offset&limit` |
| Timers (paginated, `child` filter) | `GET timers/?offset&limit&child=` |
| Sleep list / create | `GET|POST sleep/` |
| Tummy time list / create | `GET|POST tummy-times/` |
| Feeding list / create | `GET|POST feedings/` |
| Pumping list / create | `GET|POST pumping/` |
| Changes list / create | `GET|POST changes/` |
| Notes list / create | `GET|POST notes/` |
| Unused-but-defined: temperature, weight, height, head-circumference, bmi | `GET .../` |
| Delete any entry | `DELETE {type}/{id}/` |
| Timer create / list / stop | v1: `POST api/timers/`, `GET api/timers/?child=`, `DELETE api/timers/{id}/`, and v1-compat restart: `PATCH api/timers/{id}/restart/` (`BabyBuddyClient.java:600-705`) |
| Conflict lookup | v1: `GET api/<activity>/?start_max=<ts>&end_min=<ts>&limit=50&child=<id>` (`MainActivity.storeActivity`) |
| Patch entry (conflict resolution) | v1: `PATCH api/<type>/<id>/` (`updateTimelineEntry`) |

Note the API-path vs UI-path mismatch (annotations in `models/TimeEntries.kt`):
`sleep`→`sleep`, `tummy-times`→`tummy-time`, `feedings`→`feedings`, `pumping`→`pumping`, `changes`→`changes`, `notes`→`notes` (API) vs (`@UIPath`) for web URLs — see §4.5.

### 2.3 JSON field mapping per entry type (`models/TimeEntries.kt`)
- All entries: `id`, `child`, `start`, `end`, `notes` (dates `yyyy-MM-dd'T'HH:mm:ssX`, millis stripped, `Z`→`+00:00`).
- **Sleep**: standard; `notes`.
- **Tummy time**: notes go to/from **`milestone`** field.
- **Feeding**: `type` (breast milk | formula | fortified breast milk | solid food), `method` (bottle | left breast | right breast | both breasts | parent fed | self fed), `amount` (nullable double), `notes`.
- **Pumping**: legacy servers use `time` instead of start/end — model falls back `_start ?: _legacyTime` (`TimeEntries.kt:126-127`); `amount` required.
- **Change (diaper)**: uses **`time`** (single instant, `end == start`), `wet`, `solid`, `color` ("" | black | brown | green | yellow), `amount` (nullable).
- **Note**: uses **`time`** and `note` field.
- Paginated wrapper: `count`, `next`, `previous`, `results` (`PaginatedEntries`).
- `createEntry` serializes the model to a JSON tree and **removes `id`** before POST (`Client.kt:324-327`).

### 2.4 Request patterns / polling cadence
- **Timers**: polled every **1 s** for the visible child (`BabyLayoutHolder.updateTimerLoop`, `babybuddywidgets/BabyLayoutHolder.kt:147-186`).
- **Children list**: every **5 s** via `RequestScheduler` + immediately on network change; cached in local SQLite so the child list shows offline (`logic/ChildrenStateTracker.kt`).
- **Timeline**: fetch loop per activity type — 6 parallel GETs (25 items each = 150/6), merged reverse-chronologically, UI updated, then **5 s** poll; scrolling near the end triggers more fetches (`history/ChildEventHistoryLoader.kt`, `POLL_INTERVAL = 5000`).
- In-flight Retrofit calls are **cancelled on network change and re-executed in a loop** (`Client.executeCallAsync`, `Client.kt:123-179`).

---

## 3. Login & credential storage

### 3.1 Password login (`login/LoginFragment.kt` + `login/GrabAppToken.java`)
The protocol is documented in a comment at the top of `GrabAppToken.java`:
1. User enters server URL + username + password (button enabled only when all three non-empty). URL is normalized: missing scheme → prefixed `https://` (`login/Utils.kt:38-40`).
2. `GET login/` → capture `csrftoken` cookie + `csrfmiddlewaretoken` hidden input from HTML.
3. `POST login/` (urlencoded: `username`, `password`, `csrfmiddlewaretoken`, `next=/`, `Referer` header, no redirect follow).
   - `403` → "Invalid username or password"; else capture `sessionid` cookie. Server-rendered `role="alert"` div is parsed to surface Baby Buddy's own error text (`GrabAppToken.parseOutAlertPillMessage`).
4. **Get API key**: `GET api/profile` (JSON) → `api_key` field (`models/Profile.kt`). If that page is missing (404 → `MissingPage`), fallback: `GET user/settings/` and **regex-scrape the HTML** for the code next to the `api_key_regenerate` input (`GrabAppToken.parseFromSettingsPage`).
5. Store server URL + encrypted token; **validate by calling `listChildren`** (`Utils.testLoginToken`, `login/Utils.kt:71-102`); any 4xx/5xx → "Authentication failed (http response: N)" dialog, logout.
6. Navigate to `LoggedInFragment`.

There is **no use of `/api/v1/basic/`** and **no manual token paste** in the password flow.

### 3.2 QR-code login (`login/QRCodeLoginFragment.kt`, `login/QRCode.kt`, `login/LoginData.kt`)
- Payload format: **`BABYBUDDY-LOGIN:{json}`** where json = `{"url": ..., "api_key": ..., "session_cookies": {...}}` (`LoginData.fromQrcodeJSON`). Generated by the server's "Add a device" page (help doc §5).
- CameraX `ImageAnalysis` → `com.zxingcpp.BarcodeReader` (QR_CODE only, tryHarder/Rotate/Invert/Downscale).
- On scan: confirmation UI showing the URL, "Login now" button; then http-warning/home-assistant checks, store url+token+cookies, validate via `testLoginToken`, navigate.
- **HTTP warning**: if URL starts with `http:`, a warning dialog demands explicit confirmation ("Only use this feature for local Baby Buddy servers in a network that you control!") (`login/Utils.kt:19-43`). Adds a beta warning when cookies contain `ingress_session` (Home Assistant ingress).

### 3.3 Credential storage (`CredStore.java`)
- A Java **Properties file** at `<filesDir>/settings.conf` (not EncryptedSharedPreferences/Keystore directly — token is encrypted by `CredStoreEncryptionEngine` with a stored `salt`; see `TestTokenEncryptionV1`).
- Keys: `server`, `salt`, `token` (encrypted), `cookies` (encrypted base64 map), `notes_*` (per-object note drafts, `T/F:note`), `last_used_amount`, `tutorial_parameters` (base64 map), `stored_version` (drives the "Update" notice).
- Cookies are stored **encrypted** too, and re-sent as `Cookie` header on all requests (needed for HA ingress sessions).
- Logout = clear token + cookies + notes (`MainActivity.logout`, `CredStore.clearLoginData`).

---

## 4. Screen inventory & user flows

### 4.1 Navigation graph (`res/navigation/nav_graph.xml`)
`LoginFragment` (start) → `LoggedInFragment` (all-in-one dashboard; `popUpTo` inclusive so back exits). Global destinations: `AboutFragment`, `Help` (in-app slideshow), `QRCodeLoginFragment`, `DebugLogDisplay`, `ContactDeveloperFragment`, `SettingsFragment`.

Logged-in overflow menu (`res/menu/loggedin_menu.xml`, wired in `login/LoggedInMenu.kt`):
Help · Settings · Logout · Contact developer (posts to `https://www.pkgsoftware.eu/endpoints/forwardappmessage.php`, `ContactDeveloperFragment.kt:62`) · About · Export debug logs (only when `GlobalDebugObject.ENABLED`).

### 4.2 Login screens
- **Login**: server-address field (pre-filled with stored URL, or `https://babybuddy-test.pkgsoftware.eu/` under Firebase Test Lab), login name, password, "Login" button; a QR-icon button opens the scanner; keyboard GO triggers login (`login/LoginFragment.kt`).
- **QR login**: live camera preview, status text ("no code" / "invalid code" / "valid code"), scan-result card with the URL and a "Login now" button + cancel; camera-permission rationale dialog, graceful "no camera" fallback (`login/QRCodeLoginFragment.kt`).

### 4.3 Main dashboard (`LoggedInFragment` + `BabyPagerAdapter` + `BabyLayoutHolder` + `res/layout/baby_manager.xml`)
- Toolbar title = selected child's first+last name, or **"(No children found)"** (`logged_in_no_children_found`).
- One page per child; horizontal swipe switches children; selected child slug persisted (`ActivityStore.login("selected-child-slug")`, restored on relaunch).
- Each page (top→bottom): 6 quick-action buttons in an `AutoHGrid` (auto-wrapping grid of 64dp icon buttons, each with a disabled/enabled variant) → `loggingEditors` container (inline panels injected under the buttons) → "Timeline" separator → timeline list (`innerTimeline`) → progress spinner. Whole page is one ScrollView.

### 4.4 THE SIX QUICK ACTIONS (exact list & flows — `timers/TimerControllersV2.kt`, `LoggingButtonController`)
Order in layout: **Feeding, Diaper change, Sleep, Note, Pumping, Tummy time**. Confirmed: the six are **sleep, diaper change, feeding, pumping, note, tummy time** — nothing else. Each is a `SwitchButtonLogic` toggle pair (tap = on/off); tapping toggles an inline logging panel; panel state per child survives restarts via `ActivityStore.child(childId, key)`.

**Timer-based (4)** — Sleep, Feeding, Tummy time, Pumping:
1. Tap button → switches "on", panel appears. If no server timer is mapped to this activity, one is **created server-side**: `POST api/timers/ {child, name, start}` with name `"<Type>-BBapp:<n>"` (n = 1..4 for feeding/sleep/tummy/pumping; e.g. `"Sleep-BBapp:2"`) (`compat/BabyBuddyV2TimerAdapter.kt:145-231`). If an *inactive* matching timer exists (v1.x servers), it is restarted via `PATCH api/timers/{id}/restart/`, falling back to create on 404 (v2 servers have no `active` flag — timers are deleted, not stopped).
2. Timer→activity mapping by name: regex `^.*-bbapp:([0-9]+)$` (lowercased), legacy names "sleep"/"tummy time"/"feeding", or exact API names. **Any other timer on the server is ignored** (user's manual "Quick timers" don't pollute the UI).
3. Panel shows a live `HH:MM:ss` counter (updated every 500 ms, `timerHandler()`), type-specific fields, and a note editor. **Save button appears only when required fields are valid.**
4. **Save**: create the time entry via v2 API with `start = timer.start`, `end = max(timer.start, nowServer())` and the note text, then **stop = DELETE the server timer** (`GenericLoggingController.store`). Per-type payloads in §2.3.
5. Tap the button again while running = **cancel**: if the timer has run **> 10 s**, a confirmation dialog "Stop timer? The `{activity}` timer is running for a long time already…" with **Stop / Keep** (`stopTimerFromSwitch`, strings `cancel_timer_warning_*`); cancelling just deletes the timer (nothing logged).
6. On failure to save: dialog "Could not store activity" with server message (JSON errors parsed) or generic I/O text, options **Cancel / Stop timer** (`GenericLoggingController.error`).

Per-type panels:
- **Sleep** (`SleepLoggingController`): counter + note only.
- **Tummy time** (`TummyTimeLoggingController`): counter + note (stored as `milestone`).
- **Feeding** (`FeedingLoggingController`): counter + amount (`HorizontalDecIncEditor` — numeric field with big +/- buttons, step scales with magnitude, `allowNull` for "None") + note + two-stage selection: *Type buttons* (Breast milk / Formula / Fortified breast milk / Solid food) collapse into a spinner once chosen; then *Method buttons* (breast milk → Left/Right/Both breasts, Bottle, Parent fed, Self fed; others → Bottle, Parent fed, Self fed) collapse likewise; icon image updates per selection. Save enabled only when type+method both chosen (spinner states).
- **Pumping** (`PumpingLoggingController`): counter + amount (defaults 0.0, no null) + note.

**Non-timer (2)**:
- **Diaper change** (`DiaperLoggingController`): Wet / Solid toggle buttons; save button hidden until at least one is on; optional note; "extra options" expander with solid-color picker (black/brown/green/yellow + "none") and amount editor. Save = immediate `POST api/changes/` with `time = nowServer()` — no confirmation.
- **Note** (`NotesLoggingController`): text editor (auto-focused, keyboard shown), save button appears when non-empty; `POST api/notes/`.

Draft persistence (all local, cleared after successful save): diaper wet/solid/note/open-state/color/amount, feeding type/method/amount/note, pumping amount/note, generic-timer notes, and which panels are open (`LoggingButtonControllerStoreState`). Notes typed for a running timer are stored **locally only** (keyed `virttimer_<childId>_<n>` / `timer_<id>`) until the timer is saved — they are *not* pushed to the server timer.

**Conflict handling** (`MainActivity.storeActivity`, `MainActivity.kt:242-395`): if saving fails, the app queries overlapping entries for that activity (`start_max` = timer's projected server end, `end_min` = timer start, `child` filter, limit 50). If conflicts exist, a non-cancelable dialog "Overlapping time entries" offers **Modify (resolve)** — patch each conflicting entry so it ends at the timer's start, then retry save up to 3× —, **Cancel**, or **Stop timer** (discard). A progress spinner ("Storing `{activity}`…") runs throughout.

### 4.5 Timeline / history (`history/ChildEventHistoryLoader.kt`, `history/TimelineEntry.kt`)
- Reverse-chronological merged stream of the 6 implemented entry types; infinite scroll (`updateTop()` triggers fetch when within 20 items of the loaded set); new entries are inserted at top after a save (`addEntryToTop`).
- Per-type card layouts (`res/layout/timeline_item.xml`): icons for wet/solid + color swatch (diaper), breast/bottle/solid-food icon (feeding), amount lines, notes; date/time shown in device-locale SHORT format; duration shown as `H:MM` for anything ≥ 30 s; sub-30 s entries show only the start time.
- **Long-press any entry → opens the corresponding Baby Buddy web page in the browser** (`TimelineEntry.longClick` → `fragment.showUrlInBrowser(...)` → `Intent.ACTION_VIEW`, `BaseFragment.kt:298-300`). URL built in `networking/babybuddy/Client.kt:187-191`:
  `entryUserPath(entry) = serverUrl + "/" + UIPath + "/" + id + "/"` → e.g. `https://host/changes/123/`. UI paths: `sleep/<id>/`, `tummy-time/<id>/`, `feedings/<id>/`, `pumping/<id>/`, `changes/<id>/`, `notes/<id>/`.
  While pressed, a growing bubble background animates (`widgets/LongClickBackgroundBubble.kt`). A one-time tutorial hint points this out (`tutorial_long_click_notification`: "Long press a timeline entry to navigate directly to the corresponding babybuddy page in your web browser.").
- **Delete**: red delete button → confirmation dialog ("Delete entry") → `DELETE api/<type>/<id>/` (`TimelineEntry.removeClick`).
- Failed loads show a flash "error pill" message naming the activity (`history_loading_timeline_entry_failed`).

### 4.6 Settings (`SettingsFragment.kt`, `res/xml/app_settings.xml`)
A **single** preference: `setting_dark_light_mode` ListPreference (system / light / dark), applied live via `AppCompatDelegate.localNightMode` (`MainActivity.applyLightDarkMode`). No server/theme-elsewhere settings; connection changes = logout + login again. (Night qualifiers: `values-night/themes.xml`.)

### 4.7 Other screens
- **About** (`AboutFragment.kt`): generated icon-attribution list + license text (from `ATTRIBUTIONS.md`/`LICENSE.md` via gradle task).
- **Help** (`Help.kt`): 2-level ViewPager2 slideshow — horizontal topics × vertical sections, arrows, per-section title in toolbar (content in §5).
- **Contact developer** (`ContactDeveloperFragment.kt`): form sent to maintainer's PHP endpoint.
- **Debug log display** (`debugging/DebugLogDisplay.kt`): in-memory log from `GlobalDebugObject` (raw request/response logging via `DebugNetworkInterceptor`).
- **Update notice** (`UpdateNotifications.kt`): on app-version change shows an "Update `{version}`" info dialog once.

---

## 5. In-app user documentation

Source: `resources/help_images/help.md` (EN; `help-nl.md`, `help-de-rDE.md` translations) → build step → `help_strings.xml` → rendered by `Help.kt` as a swipeable slideshow reachable from the menu. Contents (topic → sections):

1. **Logging in**
   - *Login*: enter server URL, username, password, press login; or use QR option.
   - *QR Code login*: on another device logged into Baby Buddy, user menu (top-right) → "Add a device" page shows the login QR; scan it, press "Login now".
   - *Insecure Login*: screenshot of the http-warning; "Only use this feature for local BabyBuddy servers in a network that you control!"
   - *Home Assistant Ingress*: partial support, QR-login required (beta).
2. **Overview**: buttons for logging activities at the top, recent-activity history below; swipe horizontally to switch children.
3. **Using Timer Logging** (3 slides): press any timer-event button to start a timer → an interface for that timer opens; fill in information, once required fields are entered a save button appears → pressing save stops the timer and logs; pressing the top-row button again cancels the timer instead of saving.
4. **Using Event Logging**: diaper changes and notes work the same way but without a timer.
5. **Event history**: bottom list shows the child's recent activities; "edit entries by pressing the entry in the history for three seconds. This will open the corresponding page in the BabyBuddy app" (i.e. the long-press → web UI); red delete button to remove.

Website docs `doc/*.md` (linked from `doc/index.md`, which also points at the Heroku install button): `login.md` (includes demo server `http://demo.baby-buddy.net/` admin/admin; children list & swiping), `diaper.md`, `notes.md`, `history.md` match the current app. **`doc/logging.md` documents the legacy v1 UX** (a timer *list* with "create default timers" / "recreate default timers" menu options and per-timer type dropdowns); in v2 this was replaced by the quick-action grid that creates timers on demand — don't copy the v1 flow.

Overlay tutorials also exist inside the app (`tutorial/TutorialManagement.kt`): arrow+bubble hints anchored to views (e.g. burger-menu help hint on login, long-press hint on first timeline render), shown a limited number of times, counters stored in `CredStore.tutorialParameters`.

---

## 6. Edge behaviors

- **Bad server URL / server unreachable at login**: exception message surfaced in a "Login failed" dialog (`LoginFragment.performLogin`); 403 during form login → "Invalid username or password" or the server's own alert text.
- **Bad key / expired session**: `testLoginToken` fails with "Authentication failed (http response: N)" → logout. Mid-use 4xx on save → "Could not store activity" + server JSON error messages (`RequestCodeFailure.jsonErrorMessages()`).
- **Offline / network switching**: `NetworkMonitor` (ConnectivityManager callbacks) → `CoordinatedDisconnectDialog` shows a non-cancelable "Connecting to server…" spinner after a 1 s grace period, with a **Logout** button. Retrofit calls cancelled on network change and retried automatically; history uses `exponentialBackoff` (200 ms initial, ×1.5, 10 s cap) and gives up after 3 attempts with a per-activity error pill; timer poll just flags disconnect. Children list is cached in SQLite for offline display.
- **Timer continuation across restarts**: nothing timer-ish is stored client-side — timers live on the server, so a restart (or another caregiver on another device) simply re-polls and the running timer reappears with the correct elapsed time (README: "optimized for quick timer starting and stopping"; `doc/logging.md` points out multiple caregivers can stop the same timer).
- **Multiple children**: horizontal ViewPager pages; per-child selected page persisted by slug; per-child panel/draft state; children cached. Adding children must be done in the web UI (app is read-only for children).
- **Time zones / clock skew**: no assumptions about device clock — every response's `Date` header feeds a server-offset tracker (`networking/babybuddy/ServerTimeOffsetTracker.kt`: rolling 20 samples, p50/p20-derived offset; v1 uses `serverDateOffset - 100ms`). All "now" timestamps sent to the server go through `nowServer()` = local time + offset − safety margin ("what we cannot have is a time arriving at the server that is later than local server time"). Display converts back via `serverTimeToClientTime`. Serialization always UTC ISO without millis.
- **Date/time pickers: none.** Entries are always logged "now"; corrections happen via the long-press → web UI. (No `DatePicker`/`TimePicker` anywhere in `app/src/main/java`.)
- **Long-running timer cancel guard**: >10 s timers ask "Stop / Keep" before discarding (§4.4).
- **HTTP plaintext**: allowed (`usesCleartextTraffic="true"` + `res/xml/network_security_configuration.xml`) but warned at login; no cert pinning.
- **In-app update notice**: one-time "Update `{version}`" dialog after app upgrade, gated per version by `upgrade_latest_info_version_less_than_or_equal`.

---

## 7. Screenshots in the repo (cannot fetch images; filenames + where referenced)

- README: `doc/images/demo_screenie-smaller.png` (main dashboard demo).
- `doc/images/` (used by `doc/*.md`): `delete`, `demo_screenie`, `diaper_change_buttons`, `diaper_save_button`, `history_location`, `login_screen`, `notes_button`, `notes_editor`, `recreate_default_timers_option`, `timer_default_timers`, `timer_delete_old_timers`, `timer_empty_list`, `timer_feeding_screen`, `timer_feeding_screen_final`, `timer_quicktimer_mess`, `timer_select_default_timer` (each with a `-smaller` variant; the `timer_*` ones belong to the legacy v1 flow).
- `resources/help_images/screenshots/`: `help_diaper_logging.png`, `help_events.png`, `help_insecure_http_warning.png`, `help_overview_2.png`, `help_timer_interface.png` (+ `help_timer_interface.xcf` source). `resources/help_images/direct/`: `help_login_homeassistant`, `help_login_page`, `help_login_qrcode`, `help_play_button_highlight`, `help_save_button_highlight` (each `+ .xcf`).
- Play-store assets: `fastlane/metadata/android/en-US/images/` — `featureGraphic.png`, `icon.png`, `phoneScreenshots/1_en-US.png` … `4_en-US.png`.

---

## 8. ATTRIBUTIONS.md / media licensing (we must NOT copy these assets)

`ATTRIBUTIONS.md` lists all icons as **Flaticon assets with attribution-required licenses** (not MIT, unlike the code):
- Poop icons (pkg_poop/pkg_no_poop) — Good Ware; Humidity/wet (pkg_wet/pkg_not_wet) — bqlqn; Notes (pkg_notes/pkg_no_notes) — Freepik; Crawling/tummy-time (pkg_crawl) — Freepik; Diaper (pkg_diaper) — Good Ware; Sleep (pkg_sleep) — Good Ware; Baby bottle (pkg_bottle) — juicy_fish; Solid food (pkg_solid_food) — Freepik; Breast pump (pkg_pumping) — srip.
`app/build.gradle` `generateAboutStrings` parses this file into the About screen — the format is load-bearing.
`3RDPARTY.md` aggregates library licenses (Apache 2.0 for kotlinx/JetBrains et al., plus others). Conclusion for babybuddy-next: create our own icons; do not copy the Flaticon PNGs/SVGs (in `resources/`, `app/src/main/res/drawable*`), the help screenshots, or the `.xcf` sources. The app icon (`resources/icon/icon.svg`) is project-owned but part of the brand — make our own.

---

## 9. Scope checklist for babybuddy-next (what "at parity" means)

1. Login: server URL + user + password (web-session scrape of API key) **and** QR login (`BABYBUDDY-LOGIN:` payload incl. session cookies), http-warning, token validation via children list.
2. Secure local storage of token + cookies + drafts.
3. Dashboard: per-child pages (swipe), six quick actions with exact panel semantics and draft persistence, live timer counters, save-appears-when-valid.
4. Server-side timer model: create `"<Type>-BBapp:<n>"` timers, restart-on-404 handling, stop = delete, >10 s cancel confirmation, conflict detection/repair dialog.
5. Timeline: merged 6-type reverse-chronological feed, infinite scroll, polling (~5 s), delete-with-confirm, **long-press → `{server}/{uiPath}/{id}/`**.
6. Errors: offline spinner + logout, retry/backoff, error pills, server JSON error messages surfaced.
7. Multi-child + no-children state, settings (theme at minimum), help slideshow, about/attribution screen.
