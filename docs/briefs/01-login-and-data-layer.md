> **STATUS: Draft for the native-Kotlin contingency.** Framework decision pending (see docs/briefs/framework-eval/). The canonical product spec is docs/flows/new-app/00-functional-spec.md — framework-agnostic.

# Brief 01 — Login, Settings, Data Layer (online core)

**Owner:** android-engineer · **Depends on:** 00-architecture, docs/research/babybuddy-api.md, docs/research/existing-app-architecture.md

## Goal
The app can log into a Baby Buddy server, fetch children + recent events, and render a functional dashboard with the six quick actions working **online**. Offline queue comes in Brief 03 — but every write already goes through the repository layer designed for it.

## Scope
1. **Dependencies**: add Retrofit, OkHttp, kotlinx-serialization, Room, DataStore, WorkManager, Coil not needed. Add `org.jetbrains.kotlin.plugin.serialization`. Version catalog entries.
2. **SettingsRepository** (DataStore): serverUrl, apiKey, username, activeChildId, serverClockOffsetMs. Cleartext-HTTP acknowledged flag.
3. **LoginFlow** (replicate original semantics, see research doc §auth):
   - User enters server URL (trim trailing `/`, prepend `https://` if no scheme), username, password.
   - `GET {url}/login/` → extract `csrftoken` cookie + `csrfmiddlewaretoken` input; `POST {url}/login/` form-encoded with credentials + csrf + `Referer` header → session cookie.
   - `GET {url}/api/profile` with session → JSON `api_key`. Persist apiKey. All later calls use `Authorization: Token <key>`.
   - Validate by `GET {url}/api/children/`. Errors: unreachable / 401 wrong credentials / children empty (show "no children — create one on the web first") — distinct, friendly copy.
   - Cleartext http:// URLs: warning dialog before connecting ("Your data travels unencrypted. Continue?").
4. **API layer**: Retrofit interface covering (at minimum): children list, changes, feedings, sleep, notes, pumping, tummy-times (list w/ `ordering`, `limit`, `offset`, `child` filter + POST + PATCH + DELETE), timers (list/POST/DELETE/PATCH restart), profile. DTOs with exact server field names (research doc §endpoints). Error mapping: 400 body → typed `ApiValidationError(fieldErrors, nonFieldErrors)`.
5. **Local layer (Room)**: `ChildEntity`, `EventEntity` (discriminated union via `type` column + per-type columns, or polymorphic — engineer's choice, justify), `TimerEntity` (running timers, local), `OutboxOpEntity`. DAOs with Flow queries.
6. **Repositories**: `refresh()` pulls children + last N days of the six event types into Room; UI reads Room Flows only. Writes go repo → Room + outbox + (if online) immediate sync attempt.
7. **Dashboard shell (online parity)**: child switcher (all children, default first), six quick-action buttons (Feeding, Diaper, Sleep, Notes, Pumping, Tummy time — same order), timeline of recent events (newest first, day-grouped), active timers strip. Material 3, but visual polish comes later (Brief 06) — get behavior right first.
8. **Quick actions online behavior** (match original exactly, research doc §six quick actions):
   - Sleep/Feeding/Pumping/TummyTime: tap toggles a timer (creates server timer when online named `{Type}-BBapp:<n>`, restart logic per research doc), running panel shows live counter + type-specific fields, Save → POST entry (start = timer start, end = now) → DELETE server timer. Re-tap/cancel discards.
   - Feeding fields: amount editor + type→method cascade (exact combos per research doc). Pumping: amount + note. Sleep/TummyTime: note. Diaper: Wet/Solid toggles (color+amount under "extra options"), Save disabled until either toggle on. Note: text editor.
9. **Unit tests** (test-engineer review after): login flow (MockWebServer), DTO/mapper round-trips, API error mapping, repository refresh merging.

## Non-goals
Offline queue behavior (Brief 03), quick-edit sheet (Brief 04), widgets, theming polish, tests for UI.
