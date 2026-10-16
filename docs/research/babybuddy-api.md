# Baby Buddy REST API — Implementation Reference

Researched October 2026 against `babybuddy/babybuddy` master (commit `09629cf`, 2026-10-12) and the official docs at <https://docs.baby-buddy.net/api/>. Every field name, route, and validation rule below is taken directly from the source (`api/serializers.py`, `api/views.py`, `api/urls.py`, `api/filters.py`, `api/permissions.py`, `api/authentication.py`, `core/models.py`, `core/urls.py`, `babybuddy/settings/base.py`, `api/tests.py`, `docs/api.md`, `openapi-schema.yml`) — not guessed.

Local source used for verification: `~/Documents/Projects/babybuddy-next/.scratch/babybuddy`.

---

## 1. Base URL, Versioning, Schema

- **There is NO `/api/v1/` prefix.** All endpoints live directly under `/api/`. Verified against master, the `v1.10.2` tag, and the full git history (`git log -S "basic" -- api/`, `obtain_auth_token`, `api/v1` — no hits).
- `GET /api/` — API root. Lists all endpoints as `{name: url}` JSON. **This is the only endpoint that does not require authentication.**
- `GET /api/schema` (no trailing slash) — live OpenAPI schema ("Baby Buddy API", `version=1` — the version number exists only in schema metadata, not in URLs). A committed copy is at `openapi-schema.yml` in the repo root.
- `api/auth/` is mounted (`rest_framework.urls`) — this is the *browsable HTML* login/logout for the DRF web UI only. It is not a JSON auth API.
- `/api/profile` (GET, **no trailing slash**) and `/api/schema` are registered via `router.add_detail_path`, which uses `path("profile", ...)` — i.e. literally without a trailing slash.

### HTTP methods

| Method | Meaning | Success status |
|---|---|---|
| `GET` (list) | Paginated list | 200 |
| `GET` (detail) | Single object | 200 |
| `POST` (list endpoint) | Create | **201** |
| `PATCH` (detail endpoint) | Partial update (merge with stored object) | 200 |
| `DELETE` (detail endpoint) | Delete | **204, empty body** |
| `OPTIONS` | Endpoint metadata (fields, required, filters) | 200 |
| `HEAD` | — | 200 |
| `PUT` | **Always 405 Method Not Allowed.** Deliberate: `api.permissions.BabyBuddyDjangoModelPermissions.perms_map` omits `PUT`, and DRF's `DjangoModelPermissions.get_required_permissions` raises `MethodNotAllowed` for unmapped methods. Use `PATCH`. |

### Conventions

- Request/response bodies are JSON. `Content-Type: application/json` for POST/PATCH. (`multipart/form-data` is also parsed — used for file uploads such as Child `picture` / Note `image`.)
- `child` references are **integer primary keys** (`"child": 1`), not URIs, on every model. (The serializers derive from `HyperlinkedModelSerializer` but `Meta.fields` is an explicit tuple without `url`, and `child` is explicitly a `PrimaryKeyRelatedField` — so responses contain plain integers and no `url` key.)
- Datetimes: ISO 8601 with offset. Input accepts `2026-10-16T09:00:00-04:00`, `...T09:00:00Z`, or naive `2026-10-16T09:00:00` (interpreted in the instance timezone). Output is offset-form ISO, e.g. `"2017-11-18T09:00:00-05:00"`.
- Dates: `"YYYY-MM-DD"`. Times: `"HH:MM[:SS]"`.
- Durations (read-only `duration` fields and `next_dose_interval`): DRF `DurationField` — output like `"01:30:00"` / `"00:19:00"` (`[DD] [HH:[MM:]]ss[.uuuuuu]`); input accepts that form and ISO-8601 (`"PT4H"`). Test-verified: `"04:00:00"` → 4 hours.
- Float fields (`amount`, `weight`, `temperature`, …) are **unit-less floats**. The server does no unit conversion and stores no unit; what the number means (kg vs lb, mL vs fl oz) is a client/display concern.
- All numeric values may be sent as JSON strings or numbers (`"9.75"` and `9.75` both work — tests use strings).
- Boolean fields accept `true`/`false`/`1`/`0` (docs show `{"wet":1, "solid":0}`).

---

## 2. Authentication

### Token (the scheme a native client must use)

- DRF `TokenAuthentication`, via a custom subclass `api.authentication.TokenAuthentication` that additionally rejects users whose `settings.access_expires` has passed (`401`/`403`, message "Your access has expired.").
- Header: `Authorization: Token <key>` — e.g. `Authorization: Token 2h23807gd72h7hop382p98hd823dw3g665g56`.
- The token is the user's **API key**, a DRF `authtoken.Token` created lazily per user.
- Missing/invalid token → **403 Forbidden** (`{"detail": "Authentication credentials were not provided."}` / `{"detail": "Invalid token."}`). It is 403, not 401, because the first authenticator is `SessionAuthentication`, which supplies no `WWW-Authenticate` challenge. (The caregiver expiry test accepts either 401 or 403.)
- **No CSRF protection applies to token-authenticated requests.** CSRF enforcement only happens inside DRF's `SessionAuthentication`. A native client using `Authorization: Token` never needs a CSRF token.

### How to obtain the key

**There is no `POST /api/basic/` or `POST /api/v1/basic/` username/password → token endpoint.** That endpoint does not exist in any released version (checked master and `v1.10.2`). The four real ways:

1. **User Settings page** — `GET /user/settings/` (HTML) shows the key; a `Regenerate` button (form field name `api_key_regenerate`, POST to the same page) replaces it. The official Android client scrapes this page as a fallback.
2. **`GET /api/profile`** — returns the current user's key as JSON:
   ```json
   {
     "user": {"id": 1, "username": "admin", "first_name": "", "last_name": "", "email": "", "is_staff": true},
     "language": "en-US",
     "timezone": "UTC",
     "api_key": "2h23807gd72h7hop382p98hd823dw3g665g56"
   }
   ```
   This requires already being authenticated (session or token) — it is a *fetch*, not an *exchange*.
3. **QR / "Add a device" page** — `GET /user/add-device/` (HTML) shows the key plus a QR code containing exactly one line:
   `BABYBUDDY-LOGIN:{"url": "https://baby.example.com/", "api_key": "2h23…", "session_cookies": {}}`
   (`session_cookies` is empty unless Home Assistant ingress is enabled, in which case those cookies must be replayed on every request.) This is the intended pairing path for companion apps — see `docs/login-qr-code.md`.
4. **Create a caregiver via API** — `POST /api/caregivers/` (admin permission required) returns the new account's `api_key` **once**, in the 201 response.

### What the official Android client actually does (cross-checked, `.scratch/babybuddy-for-android`, `GrabAppToken.java`)

1. `GET /login/` → collect `Set-Cookie: csrftoken=…` and the hidden form field `csrfmiddlewaretoken`.
2. `POST /login/` form-encoded with `csrfmiddlewaretoken`, `username`, `password`, `next=/`, headers `Cookie: csrftoken=…` and `Referer` = login page URL. Non-200/3xx or missing `sessionid` cookie ⇒ bad credentials.
3. With the `sessionid` cookie: `GET /api/profile` → parse `api_key`.
4. All subsequent calls: `Authorization: Token <api_key>` against base `<server>/api/`.

A new Android client can implement exactly this, or skip the session dance and have the user paste the key / scan the QR code.

### Permissions model

`api.permissions.BabyBuddyDjangoModelPermissions` maps methods to Django model permissions:

| Method | Required permission |
|---|---|
| GET | `core.view_<model>` |
| OPTIONS | `core.add_<model>` (yes, really) |
| HEAD | none |
| POST | `core.add_<model>` |
| PATCH | `core.change_<model>` |
| DELETE | `core.delete_<model>` |
| PUT | (not mapped → 405) |

Built-in groups (`babybuddy.settings.base.BABY_BUDDY`): `caregiver` (add/view/change everything, **no delete**, no tag create/rename unless granted, no user/tag/caregiver management) and `read_only` (view only). Tag operations are permission-gated per request: changing a note's `tags` needs `core.change_tag` (and `core.add_tag` to introduce new tag names) → otherwise **403** `{"detail": "You do not have permission to change tags."}`.

---

## 3. Endpoint Registry

Registered in `api/urls.py` on `path("api/", include(router.urls))`. Detail route = list prefix + `/{id}/` (children and tags use their lookup field instead).

| Resource | List endpoint | Detail lookup | Default ordering | `ordering_fields` |
|---|---|---|---|---|
| BMI | `/api/bmi/` | id | `-date` | child, date |
| Caregiver | `/api/caregivers/` | id | `username` | username, date_joined |
| Diaper Change | `/api/changes/` | id | `-time` | amount, time |
| Child | `/api/children/` | **slug** (e.g. `/api/children/gregory-hill/`) | `-birth_date,-birth_time` | birth_date, birth_time, first_name, last_name, slug |
| Feeding | `/api/feedings/` | id | `-end` | amount, duration, end, start |
| Head Circumference | `/api/head-circumference/` | id | `-date` | date, head_circumference |
| Height | `/api/height/` | id | `-date` | date, height |
| Medication | `/api/medication/` | id | `-time` | time, name, dosage |
| Note | `/api/notes/` | id | `-time` | time |
| Pumping | `/api/pumping/` | id | `-end` | amount, duration, end, start |
| Sleep | `/api/sleep/` | id | `-end` | duration, end, start |
| Tag | `/api/tags/` | **slug** | `name` | last_used, name, slug |
| Temperature | `/api/temperature/` | id | `-time` | temperature, time |
| Timer | `/api/timers/` | id | `-start` | duration, end, start |
| Tummy Time | `/api/tummy-times/` | id | `-start` | duration, end, start |
| Webhook endpoint | `/api/webhook-endpoints/` | id | `name` | name, created, last_delivery |
| Weight | `/api/weight/` | id | `-date` | date, weight |
| Profile | `/api/profile` (no slash) | — (GET only) | — | — |
| Schema | `/api/schema` (no slash) | — | — | — |

Extra detail action: `PATCH /api/timers/{id}/restart/`.

Notes:

- The **model name ≠ endpoint name** in one direction worth knowing: Diaper Change model is `DiaperChange` but the endpoint is `/api/changes/`; Tummy Time UI URLs use singular `tummy-time/` while the API uses `tummy-times/`.
- `caregivers` supports list/create/retrieve/update only — `DELETE` → 405. `POST /api/caregivers/` requires admin; response includes `api_key` exactly once.
- Naming quirk in `api/tests.py`: reverse names are `api:diaperchange-list`, `api:tummytime-list`, `api:feeding-list`, `api:sleep-list`, etc.

### Pagination

`LimitOffsetPagination` with `PAGE_SIZE = 100` (default page size when `?limit=` omitted):

```
GET /api/changes/?limit=5&offset=10
{
  "count": 20,                                                    # total records in DB
  "next": "https://host/api/changes/?limit=5&offset=15",          # or null
  "previous": "https://host/api/changes/?limit=5&offset=5",       # or null
  "results": [ {...}, ... ]
}
```

- Ordering: `?ordering=-end`, `?ordering=start`, `?ordering=duration` (only the fields in the `ordering_fields` column above).
- Filtering: see per-resource tables in §4. `filters` are also advertised in each endpoint's `OPTIONS` response.
- Filter parameter semantics (`api/filters.py`):
  - `child=<id>` exact FK.
  - DateTime range filters are `IsoDateTimeFilter`: `start`, `start_min` (`gte`), `start_max` (`lte`), `end`, `end_min`, `end_max` — accept ISO datetime or a plain date (`"2017-11-18"` matches that whole day).
  - Time-based models use the param name `date`, `date_min`, `date_max` for their `time` field (Changes, Notes, Temperature, Medication).
  - `tags=a,b` — comma-separated tag names, exact match on `tags__name` (where offered).
  - `wet=<bool>`, `solid=<bool>`, `color=`, `amount=`, `type=`, `method=`, `name=`, `dosage_unit=`, `user=<id>` exact matches.

| Endpoint | Filter params |
|---|---|
| `/api/changes/` | child, date, date_min, date_max, wet, solid, color, amount, tags |
| `/api/feedings/` | child, start, start_min, start_max, end, end_min, end_max, type, method, tags |
| `/api/sleep/` | child, start, start_min, start_max, end, end_min, end_max, tags |
| `/api/tummy-times/` | child, start, start_min, start_max, end, end_min, end_max, tags |
| `/api/pumping/` | child, start, start_min, start_max, end, end_min, end_max |
| `/api/notes/` | child, date, date_min, date_max, tags |
| `/api/temperature/` | child, date, date_min, date_max, tags |
| `/api/medication/` | child, date, date_min, date_max, name, dosage_unit, tags |
| `/api/timers/` | child, start, start_min, start_max, end, end_min, end_max, name, user |
| `/api/bmi/`, `/api/height/`, `/api/weight/`, `/api/head-circumference/` | child, date |
| `/api/children/` | id, first_name, last_name, slug, birth_date, birth_time |
| `/api/tags/` | name, last_used |
| `/api/webhook-endpoints/` | active |
| `/api/caregivers/` | is_active |

---

## 4. Field Reference (per resource)

Legend: **R** = required on POST; O = optional; RO = read-only; WO = write-only. "R*" = runtime-required by the duration serializer's `validate()` (not marked required in OpenAPI) — child/start/end may all be omitted only if `timer` is supplied.

### Child — `/api/children/`

| Field | Type | Req | Notes |
|---|---|---|---|
| `id` | int | RO | |
| `first_name` | string (max 255) | R | |
| `last_name` | string (max 255) | O | may be empty |
| `birth_date` | date | R | not in future |
| `birth_time` | time | O | |
| `due_date` | date | O | used for corrected-age growth charts |
| `slug` | string | auto | Generated from name on save; unique; **renaming a child via PATCH changes the slug**; not editable via API |
| `picture` | image file | O | multipart upload |

No `tags` on Child. Detail route: `/api/children/<slug>/`.

### Sleep — `/api/sleep/`  (duration model)

| Field | Type | Req | Notes |
|---|---|---|---|
| `id` | int | RO | |
| `child` | int (Child FK) | R* | omitted allowed only when `timer` supplies it |
| `start` | datetime | R* | not in future |
| `end` | datetime | R* | not in future; must be > start |
| `timer` | int (Timer id) | WO | alternative to child/start/end; see §6 |
| `duration` | string | RO | computed `end - start` |
| `nap` | bool | O (nullable) | if omitted/null: auto-computed — true when local start time is within the configured nap window (default 06:00–18:00, site setting "Nap settings") |
| `notes` | string | O | |
| `tags` | string[] | O | permission-gated |

OpenAPI lists `required: []` for Sleep because child/start/end are declared `required: False`; **at runtime all three are mandatory unless `timer` is given** (`400 {"child": ["This field is required."]}` etc.).

### Feeding — `/api/feedings/`  (duration model)

| Field | Type | Req | Notes |
|---|---|---|---|
| `id`, `child`, `start`, `end`, `timer`, `duration`, `notes`, `tags` | — | as Sleep | `start` not in future (`end` is *not* future-checked) |
| `type` | string choice | **R** | `"breast milk"` \| `"formula"` \| `"fortified breast milk"` \| `"solid food"` |
| `method` | string choice | **R** | `"bottle"` \| `"left breast"` \| `"right breast"` \| `"both breasts"` \| `"parent fed"` \| `"self fed"` |
| `amount` | float | O | e.g. mL or fl oz — unit-less |

Correction to the task brief: method values are the six listed above — there is no `"breast"` or `"unknown"` method, and `type` is a *required* separate field.

### Pumping — `/api/pumping/`  (duration model)

| Field | Type | Req | Notes |
|---|---|---|---|
| `id`, `child`, `start`, `end`, `timer`, `duration`, `notes`, `tags` | — | child/start/end R* | |
| `amount` | float | **R** | |

`start` not in future (`end` not future-checked).

### Diaper Change — `/api/changes/`  (instant model)

| Field | Type | Req | Notes |
|---|---|---|---|
| `id` | int | RO | |
| `child` | int | **R** | |
| `time` | datetime | O | default = now |
| `wet` | bool | **R** | |
| `solid` | bool | **R** | |
| `color` | string choice | O | `""` \| `"black"` \| `"brown"` \| `"gray"` \| `"green"` \| `"orange"` \| `"red"` \| `"white"` \| `"yellow"` |
| `amount` | float | O | |
| `notes` | string | O | |
| `tags` | string[] | O | |

No overlap checking — any number of changes at the same time is accepted. `time` must not be in the future.

### Note — `/api/notes/`

| Field | Type | Req | Notes |
|---|---|---|---|
| `id` | int | RO | |
| `child` | int | **R** | |
| `note` | string | **R** | |
| `image` | image file | O | multipart upload |
| `time` | datetime | O | default = now; note: PATCHing a note also updates its `time` (model has `default=now` and the web UI re-stamps) — the test suite notes "The time of entry will always update automatically" on PATCH |
| `tags` | string[] | O | |

### Tummy Time — `/api/tummy-times/`  (duration model)

| Field | Type | Req | Notes |
|---|---|---|---|
| `id`, `child`, `start`, `end`, `timer`, `duration`, `notes`, `tags` | — | child/start/end R* | both start and end future-checked |
| `milestone` | string (max 255) | O | |

### Temperature — `/api/temperature/`

`child` **R**, `temperature` float **R**, `time` datetime O (default now, not future), `notes` O, `tags` O, `id` RO.

### Weight — `/api/weight/`

`child` **R**, `weight` float **R**, `date` date O (default today, not future), `notes` O, `tags` O, `id` RO.

### Height — `/api/height/`

`child` **R**, `height` float **R**, `date` date O (default today, not future), `notes` O, `tags` O, `id` RO.

### Head Circumference — `/api/head-circumference/`

`child` **R**, `head_circumference` float **R**, `date` date O (default today, not future), `notes` O, `tags` O, `id` RO.

### BMI — `/api/bmi/`

`child` **R**, `bmi` float **R**, `date` date O (default today, not future), `notes` O, `tags` O, `id` RO.

### Medication — `/api/medication/`

| Field | Type | Req | Notes |
|---|---|---|---|
| `id` | int | RO | |
| `child` | int | **R** | |
| `name` | string (max 255) | **R** | |
| `dosage` | float | O | |
| `dosage_unit` | string choice | O | `""` \| `"mg"` \| `"ml"` \| `"tablets"` \| `"drops"` |
| `time` | datetime | O | default = now, not future |
| `next_dose_interval` | duration string | O | e.g. `"04:00:00"` |
| `notes` | string | O | |
| `tags` | string[] | O | |

### Timer — `/api/timers/`

| Field | Type | Req | Notes |
|---|---|---|---|
| `id` | int | RO | |
| `child` | int \| null | O | nullable — a timer may be childless |
| `name` | string \| null | O | max 255; `""` stored as `null`; **not unique** in any way (no constraint in model or form) |
| `start` | datetime | O | default = now; not in the future |
| `duration` | duration string | RO | computed at read time: `now - start` |
| `user` | int (User id) | O | defaults to requesting user on create; changing it to another user requires `core.delete_timer` else 403 |

No `end` and no `active` field are exposed (the model's `active` column always stays `true`; "stopping" a timer *deletes* it — §6). Multiple simultaneous timers are allowed.

### Tag — `/api/tags/`

`slug` RO (lookup), `name` **R**, `color` O (default random; must match `^#[0-9a-fA-F]{6}$`), `last_used` RO. Caregivers cannot list/create tags (403).

### Caregiver — `/api/caregivers/` (admin only)

`username` **R**; `email`, `first_name`, `last_name`, `is_active`, `access_expires` O. 201 response additionally carries `api_key` (once only). No delete (405) — withdraw access with `is_active: false`.

### Webhook endpoints / profile

Out of scope for a tracking client; see `docs/configuration/webhooks.md` and §2 (`/api/profile`).

---

## 5. Validation & Conflict Rules (what gets rejected, and how)

All model `clean()` validation runs on **both POST and PATCH** (the serializer merges PATCH data onto the instance before calling `clean()`). Errors → **HTTP 400** with DRF's standard body: a JSON object keyed by field name (or `non_field_errors` for cross-field errors), each value a **list of strings**:

```json
{"end": ["Date/time can not be in the future."]}
{"non_field_errors": ["Duration too long."]}
```

### 5.1 Overlap / conflict rules — the ones that matter for an offline-syncing client

`validate_unique_period` applies to exactly four models: **Sleep, Feeding, Pumping, TummyTime**. An entry is rejected if any *other* entry **for the same child** satisfies `existing.start < new.end AND existing.end > new.start` (half-open intersection; touching ranges, e.g. 08:00–09:00 and 09:00–10:00, do NOT conflict). The DB queryset is `Model.objects.filter(child_id=self.child_id)`; on PATCH the entry being edited is excluded by id.

The 400 body is a single `non_field_errors` message that **contains an HTML link** to the conflicting entry's web edit page (built with `mark_safe`):

```json
{
  "non_field_errors": [
    "Another entry intersects the specified time period. Conflicting entry: <a href=\"/sleep/4/\">Sleep (11/19/17 03:00 - 11/19/17 04:30)</a>"
  ]
}
```

(Client should strip the markup / optionally parse the href to deep-link the user to the conflict. The datetime format inside is localized `SHORT_DATETIME_FORMAT`.)

**NOT overlap-checked:** DiaperChange, Temperature, Weight, Height, Head Circumference, BMI, Note, Medication, Timer. Duplicates are accepted.

### 5.2 Future-time rules

| Model | Fields checked | 400 message |
|---|---|---|
| Sleep, TummyTime | `start` and `end` | `{"start": ["Date/time can not be in the future."]}` / `{"end": [...]}` |
| Feeding, Pumping | `start` only (end is **not** future-checked) | `{"start": [...]}` |
| DiaperChange, Temperature, Medication | `time` | `{"time": [...]}` |
| Timer | `start` | `{"start": [...]}` |
| Weight, Height, HeadCircumference, BMI | `date` | `{"date": ["Date can not be in the future."]}` |

### 5.3 Duration sanity (Sleep, Feeding, Pumping, TummyTime) — `non_field_errors`

- `start >= end` → `"Start time must come before end time."` (code `end_before_start`)
- `end - start > 24 hours` → `"Duration too long."` (code `max_duration`)

### 5.4 Field/type errors

- Missing required field → `{"<field>": ["This field is required."]}`.
- Invalid choice → `{"type": ["\"water\" is not a valid choice."]}`.
- Malformed datetime → `{"start": ["Datetime has wrong format. Use one of these formats instead: …"]}`.
- Empty body / wrong types → 400 with parse error `{"detail": "JSON parse error - …"}` or field errors.

### 5.5 Timer-consumption errors (POST with `timer`)

| Situation | Response |
|---|---|
| `"timer": null`, `"timer": "bad"`, `"timer": 999999` | 400 (`{"timer": ["This field may not be null."]}` / invalid / does-not-exist) |
| Timer has no child and request has no `child` | 400 `{"child": ["This field is required."]}` — timer is **not** consumed |
| Timer owned by another user, requester lacks `core.delete_timer` | 403 `{"detail": "You do not have permission to consume timers."}` — timer kept |
| Timer deleted between validation and save (race) | 400 `{"timer": ["This timer no longer exists."]}`; consumption is `select_for_update` + `transaction.atomic`, so a failed save rolls back and keeps the timer |

### 5.6 Other notable rejections

- Timer `PATCH` that changes `user` to someone else without `core.delete_timer` → 403 `{"detail": "You do not have permission to change the user of a timer."}`.
- Caregiver (default group) `DELETE` of anything → 403 `{"detail": "You do not have permission to perform this action."}`; `GET /api/tags/` as caregiver → 403.
- Not found → 404 `{"detail": "Not found."}`. Unauthenticated → 403 (§2). Wrong method → 405.
- Child slug collisions (two children with the same name) hit the DB `unique` constraint → unhandled IntegrityError (500); avoid creating same-named children.
- Throttling: none configured.

---

## 6. Timer Mechanics

Model (`core/models.py` `Timer`): `child` (nullable FK), `name` (nullable), `start` (default now), `active` (default True, **not exposed via API**), `user` (FK, default = creating user). `duration()` = `now - start`. `restart()` sets `start = now`. **`stop()` = `delete()`** — stopping a timer deletes the row; there is no "ended" state.

API mapping:

| Action | Call |
|---|---|
| Start a timer | `POST /api/timers/` `{"child": 1, "name": "Nap timer"}` → 201 `{ "id": 5, "child": 1, "name": "Nap timer", "start": "2026-10-16T19:59:40.013914Z", "duration": null, "user": 1 }` (child/name/user all optional; bare `{}` POST is valid) |
| Read elapsed | `GET /api/timers/5/` (`duration` computed server-side at read) |
| Rename / reassign child / edit start | `PATCH /api/timers/5/` |
| Restart (back to 0) | `PATCH /api/timers/5/restart/` (allowed repeatedly) |
| **Stop (discard)** | `DELETE /api/timers/5/` → 204, row deleted |
| **Stop by saving as an entry** | `POST /api/sleep/` (or feedings / tummy-times / pumping) with `{"timer": 5, ...}` — see below |

### Sleep (etc.) from a timer

`CoreModelWithDurationSerializer` behavior, exact:

- Send `{"timer": 5}` (+ any fields the timer can't supply). `timer` is **write-only** (never echoed).
- If the timer has a `child`, it wins; otherwise the request must supply `child` (else 400).
- `start` := timer's `start`; `end` := **server "now"**. These **override** any `child`/`start`/`end` sent in the same request.
- After the entry is saved atomically, the timer is stopped **by deletion**. Validation failure → timer survives; the entry and timer stop succeed or fail together.
- The entry then flows through normal validation (future times, ≤ 24 h, overlap with the child's other entries — a timer left running over an existing sleep entry *will* 400 with the intersection error).

"Sleep start / sleep stop" for a native app is therefore: start = `POST /api/timers/` `{"child": N}` and remember the id; stop = `POST /api/sleep/` `{"timer": id}` (optionally with `notes`); cancel = `DELETE /api/timers/{id}/`.

---

## 7. Web UI URL Patterns (deep-linking; `core/urls.py`)

Edit page for an object is the plain detail URL — **not** `/…/<id>/change/`:

| Type | Add | Edit (detail) | Delete |
|---|---|---|---|
| Child | `/children/add/` | `/children/<slug>/` (dashboard), `/children/<slug>/edit/` | `/children/<slug>/delete/` |
| Diaper change | `/changes/add/` | `/changes/<id>/` | `/changes/<id>/delete/` |
| Feeding | `/feedings/add/` (bottle variant: `/feedings/bottle/add/`) | `/feedings/<id>/` | `/feedings/<id>/delete/` |
| Sleep | `/sleep/add/` | `/sleep/<id>/` | `/sleep/<id>/delete/` |
| Note | `/notes/add/` | `/notes/<id>/` | `/notes/<id>/delete/` |
| Tummy time | `/tummy-time/add/` | `/tummy-time/<id>/` | `/tummy-time/<id>/delete/` |
| Timer | `/timers/add/`, `/timers/add/quick/` | `/timers/<id>/` (detail), `/timers/<id>/edit/`, `/timers/<id>/restart/` | `/timers/<id>/delete/` |
| Temperature | `/temperature/add/` | `/temperature/<id>/` | `/temperature/<id>/delete/` |
| Weight | `/weight/add/` | `/weight/<id>/` | `/weight/<id>/delete/` |
| Height | `/height/add/` | `/height/<id>/` | `/height/<id>/delete/` |
| Head circumference | `/head-circumference/add/` | `/head-circumference/<id>/` | `/head-circumference/<id>/delete/` |
| BMI | `/bmi/add/` | `/bmi/<id>/` | `/bmi/<id>/delete/` |
| Medication | `/medication/add/` | `/medication/<id>/` | `/medication/<id>/delete/` |
| Pumping | `/pumping/add/` | `/pumping/<id>/` | `/pumping/<id>/delete/` |

Related app pages: `/dashboard/`, `/timeline/`, `/user/settings/` (API key), `/user/add-device/` (key + QR), `/login/`, `/users/…` (admin). The conflict error message's HTML link points at these edit URLs (e.g. `/sleep/4/`).

---

## 8. Example Requests / Responses

All authenticated: `Authorization: Token <key>`; `Content-Type: application/json`. Success POST = 201, PATCH = 200, DELETE = 204 (empty).

### 8.1 Diaper change

```http
POST /api/changes/
{"child": 1, "time": "2026-10-16T08:30:00-04:00", "wet": true, "solid": false,
 "color": "yellow", "amount": 2.5, "notes": "seedy", "tags": ["morning"]}
```
```json
{"id": 948, "child": 1, "time": "2026-10-16T08:30:00-04:00", "wet": true,
 "solid": false, "color": "yellow", "amount": 2.5, "notes": "seedy", "tags": ["morning"]}
```
Minimal variant (time defaults to now): `{"child": 1, "wet": true, "solid": false}`.

### 8.2 Bottle feed

```http
POST /api/feedings/
{"child": 1, "start": "2026-10-16T09:00:00-04:00", "end": "2026-10-16T09:15:00-04:00",
 "type": "formula", "method": "bottle", "amount": 120, "notes": null}
```
```json
{"id": 51, "child": 1, "start": "2026-10-16T09:00:00-04:00",
 "end": "2026-10-16T09:15:00-04:00", "duration": "00:15:00", "type": "formula",
 "method": "bottle", "amount": 120.0, "notes": null, "tags": []}
```

### 8.3 Pumping session

```http
POST /api/pumping/
{"child": 1, "start": "2026-10-16T15:03:00-04:00", "end": "2026-10-16T15:22:00-04:00",
 "amount": 90, "notes": "left side"}
```
```json
{"id": 7, "child": 1, "amount": 90.0, "start": "2026-10-16T15:03:00-04:00",
 "end": "2026-10-16T15:22:00-04:00", "duration": "00:19:00", "notes": "left side", "tags": []}
```
(`amount` is the only explicitly required field; child/start/end are runtime-required unless `timer` is used.)

### 8.4 Note

```http
POST /api/notes/
{"child": 1, "note": "First smile!", "time": "2026-10-16T10:00:00-04:00"}
```
```json
{"id": 12, "child": 1, "note": "First smile!", "image": null,
 "time": "2026-10-16T10:00:00-04:00", "tags": []}
```

### 8.5 Tummy time

```http
POST /api/tummy-times/
{"child": 1, "start": "2026-10-16T12:30:00-04:00", "end": "2026-10-16T12:35:30-04:00",
 "milestone": "Rolled over."}
```
```json
{"id": 163, "child": 1, "start": "2026-10-16T12:30:00-04:00",
 "end": "2026-10-16T12:35:30-04:00", "duration": "00:05:30",
 "milestone": "Rolled over.", "notes": null, "tags": []}
```

### 8.6 Sleep — explicit start/end

```http
POST /api/sleep/
{"child": 1, "start": "2026-10-15T19:30:00-04:00", "end": "2026-10-15T23:00:00-04:00",
 "notes": "used new swaddle"}
```
```json
{"id": 481, "child": 1, "start": "2026-10-15T19:30:00-04:00",
 "end": "2026-10-15T23:00:00-04:00", "duration": "03:30:00", "nap": false,
 "notes": "used new swaddle", "tags": []}
```
(`nap` omitted → auto-computed from the start-time window; supply `"nap": true/false` to override.)

### 8.7 Sleep — start & stop via timer (the app-style flow)

```http
POST /api/timers/
{"child": 1}
```
```json
{"id": 5, "child": 1, "name": null, "start": "2026-10-16T19:59:40.013914Z",
 "duration": null, "user": 1}
```
…later:
```http
POST /api/sleep/
{"timer": 5, "notes": "timer flow"}
```
```json
{"id": 482, "child": 1, "start": "2026-10-16T19:59:40.013914Z",
 "end": "2026-10-16T21:31:13.549099Z", "duration": "01:31:33.535185",
 "nap": true, "notes": "timer flow", "tags": []}
```
Timer 5 is now deleted (`GET /api/timers/5/` → 404). If instead the timer was childless, the sleep POST must also carry `"child": N` or it returns 400 `{"child": ["This field is required."]}` without consuming the timer.

### 8.8 Update & delete

```http
PATCH /api/changes/947/
{"wet": true, "solid": false}
```
→ 200 with the full updated object.
```http
DELETE /api/sleep/481/
```
→ 204, empty body.

---

## 9. Notes for a Native Client

- **CORS**: `django-cors-headers` is installed but inert unless the server sets the `CORS_ALLOWED_ORIGINS` env var. Irrelevant for native apps; matters only for browser-based clients.
- **CSRF**: not required with token auth. Only the HTML `POST /login/` session dance needs `csrftoken` cookie + `csrfmiddlewaretoken` + `Referer`.
- **Bearer-style flows don't exist**: no `POST /api/v1/basic/`, no refresh tokens, no OAuth. Long-lived token per user; rotation only via the settings page (`Regenerate`) which immediately invalidates the old key.
- **PUT is 405 everywhere** — always PATCH.
- **Polling / sync**: use `?limit=&offset=` + `?ordering=<field>` + `<field>_min/_max` ISO datetime filters for delta sync; watch `count` for new/removed rows. Note list endpoints' default ordering differs from model ordering (e.g. feedings default `-end`).
- **Offline-created entries**: server-side validation (§5) may reject on push — treat `400 non_field_errors` containing "intersects" as a conflict against an entry created meanwhile (the message embeds a web-UI link to the conflicting row).
- **Timer end is server-time**: when consuming a timer, `end = now` on the *server*; clock skew between device and server is reflected in the entry.
- **24-hour cap**: any duration entry spanning > 24 h is rejected; split longer sleeps.
- **Caregiver accounts** have no delete rights and no tag-management rights unless explicitly granted; a client should degrade gracefully (hide delete UI) on 403.
- `OPTIONS` on any endpoint is a good runtime capability probe: it lists every POST field with `type/required/read_only/label` and the `filters` list.
