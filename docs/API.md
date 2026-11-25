# Synchaven REST API (v1)

Base URL: `http://<host>:8080/api` · Format: JSON (UTF-8) · Times: RFC 3339 / ISO 8601.

The server's user-facing texts (error messages, import reports) are in the language of the request's `Accept-Language`
header – currently English (`en`, the default) or German (`de`). Log output is always English. Texts the server
stores for a user (names of default calendars, lists and address books, birthday events) use the language saved with
the account (`language` in `User`): chosen on registration, changeable with `PUT /auth/me`. The texts are in
`backend/locales/` (one JSON file per language).

## Authentication

All endpoints except `/info`, `/auth/register` and `/auth/login` require the header

```
Authorization: Bearer <token>
```

Tokens are JWTs (HS256) and valid for 30 days by default (`TOKEN_TTL_DAYS`, can be changed in the admin page). If the
token is expired or invalid, the server responds with `401` – the app should then return to sign-in.
This also applies when an administrator has disabled the account, signed it out everywhere or reset its password.

## Error format

```json
{ "error": "bad_request", "message": "The title must be 1–500 characters long" }
```

| HTTP | `error`        | Meaning                                    |
|------|----------------|--------------------------------------------|
| 400  | `bad_request`  | Invalid input (`message` can be displayed) |
| 401  | `unauthorized` | Not signed in / wrong password / account disabled / session ended |
| 403  | `forbidden`    | e.g. registration disabled, sign-in of a disabled account |
| 404  | `not_found`    | Does not exist or belongs to another user  |
| 401  | `totp_required` | Correct password, but the account has two-factor authentication: send `code` as well |
| 401  | `totp_invalid` | The code of the second factor is wrong or was already used |
| 409  | `conflict`     | Email already registered                   |
| 429  | `too_many_requests` | Too many failed sign-ins for this email address, or too many wrong codes of the second factor (5 within 5 minutes per account) |
| 500  | `internal`     | Server error                               |

## Time model

* **Timed events**: `start`/`end` are absolute points in time (always UTC in responses, `…Z`).
  Input may use any offset (`2026-11-23T09:00:00+01:00`).
  `timezone` (IANA, e.g. `Europe/Berlin`) determines the time zone in which recurrences are computed
  – so a weekly event at 09:00 stays at 09:00 after a DST change.
* **All-day events** (`all_day: true`): `start`/`end` are midnight **UTC** of the date,
  `end` is **exclusive**. A one-day event on Dec 24: `start = 2026-12-24T00:00:00Z`,
  `end = 2026-12-25T00:00:00Z`. Clients only read the date part.
* **Recurrence**: `rrule` is an RFC 5545 RRULE without prefix, e.g. `FREQ=WEEKLY;BYDAY=MO,WE;COUNT=10`.
  `UNTIL` may be passed as a date (`UNTIL=20261231`) and is converted to UTC on the server.
  `exdates` contains the start times of skipped occurrences.

---

## General

### `GET /info`
```json
{ "name": "Synchaven", "version": "1.0.0", "api_version": 1, "allow_registration": true, "setup_required": false }
```
`setup_required` is `true` as long as no account exists: the first account (the administrator) is then created with
`setup_token` in `POST /auth/register`. `allow_registration` is `false` during the setup.

## Account

### `POST /auth/register` → `201`
```json
{ "email": "anna@example.com", "password": "at least 8 characters", "name": "Anna", "language": "de", "setup_token": "…" }
```
`setup_token` is only needed for the first account (`setup_required` in `/info`): the token the server writes to its
log and to `setup_token` in the data directory. Without it or with a wrong one: `403`.
`language` (`de` or `en`, optional) is the language of the account's initial data; without it, the request's
`Accept-Language` decides. Another value gives `400`.

Response (also for login):
```json
{
  "token": "eyJ…",
  "token_type": "Bearer",
  "expires_at": "2026-12-22T18:56:04Z",
  "user": { "id": "…", "email": "anna@example.com", "name": "Anna", "default_view": "month", "language": "de", "created_at": "…", "is_admin": false, "totp_enabled": false }
}
```
`default_view` is the calendar's default view (`month`, `week`, `day` or `list`). The web UI and the app
store the last selected view there. `is_admin` is `true` for administrators – initially the first registered
account; further ones are granted in the admin page. New accounts get their initial data in `language`: a calendar
("Persönlich" / "Personal"), a task list ("Meine Aufgaben" / "My tasks"), an address book ("Kontakte" / "Contacts")
and the birthday calendar ("Geburtstage" / "Birthdays"). Further accounts can only register while registration is open (admin page or `ALLOW_REGISTRATION=true`;
`allow_registration` in `/info` shows whether it is currently possible), otherwise `403`. A disabled
account gets `403` when signing in with the correct password.

### `POST /auth/login`
```json
{ "email": "anna@example.com", "password": "…", "code": "123456" }
```
`code` is only needed for accounts with two-factor authentication (`totp_enabled`): the 6-digit code of the
authenticator app or one of the recovery codes (`xxxxx-xxxxx`, dashes and case don't matter). Without `code`, such an
account gets `401` with `error: "totp_required"` after a correct password – the client then asks for the code and
sends the request again with it. A wrong or already used code gives `401` with `totp_invalid`; each code is accepted
only once. After 5 wrong codes within 5 minutes, further codes for the account are rejected with `429` for the rest of
those 5 minutes.

Failed sign-ins are counted per email address (unknown addresses too, so the answer doesn't reveal whether an account
exists); CardDAV sign-ins with the account password count towards the same limit. The first 5 failures are free, after
that each attempt has to wait 30 seconds, doubling up to 15 minutes; earlier attempts get `429` (`too_many_requests`,
the `message` says how many minutes). A correct password resets the count, failures are forgotten after an hour without
attempts. Existing sessions, app passwords and CardDAV clients that signed in successfully during the last 15 minutes
are not affected.

### `POST /auth/refresh` → like login
Returns a new token (same response as `/auth/login`) for a session that is still valid. The Android app uses this
to renew the token of its sync without storing the password.

### `GET /auth/me` → `User`

### `PUT /auth/me` → `User`
All fields optional. `current_password` is required for changing `email` or `new_password`. A new password ends all
sessions of the account (all tokens issued until now become invalid, other devices get `401`), deletes all its app
passwords and push subscriptions; the response then additionally contains `token` and `expires_at` – the client
continues with this new token (and the web UI re-subscribes its browser for push).
```json
{ "name": "Anna B.", "email": "new@example.com", "current_password": "…", "new_password": "…", "default_view": "week", "language": "en" }
```
Changing `language` updates the texts of the birthday events; existing calendars, lists and address books keep their
names.

## Two-factor authentication (TOTP)

Codes follow RFC 6238 (SHA-1, 6 digits, 30 seconds, ±30 seconds tolerance) – supported by every authenticator app.
Changes need the account password (`current_password`) again; a wrong one gives `401` (`unauthorized`).

### `GET /auth/totp`
```json
{ "enabled": true, "recovery_codes_left": 9 }
```

### `POST /auth/totp/setup`
`{ "current_password": "…" }` – starts the setup with a new secret (`409` if two-factor authentication is already on):
```json
{ "secret": "JBSWY3DPEHPK3PXP…", "otpauth_uri": "otpauth://totp/Synchaven:anna@example.com?secret=…", "qr_code": "data:image/svg+xml;base64,…" }
```
`qr_code` shows `otpauth_uri` as a QR code for scanning; on phones `otpauth_uri` opens the authenticator app directly.

### `POST /auth/totp/enable`
`{ "code": "123456" }` – confirms the setup with a code of the new secret. Only then is two-factor authentication on.
Response: `{ "recovery_codes": ["kjtfb-h74hx", …] }` – 10 codes, shown only this once; each one replaces a code of the
app once.

### `POST /auth/totp/disable` → `204`
`{ "current_password": "…", "code": "123456" }` – `code` may also be a recovery code.

### `POST /auth/totp/recovery-codes`
`{ "current_password": "…", "code": "123456" }` – new recovery codes (response like `enable`); the old ones become invalid.

## App passwords

Random passwords for sync clients that cannot enter a code (CardDAV). With two-factor authentication on, CardDAV only
accepts app passwords; without it, both the account password and app passwords work.

`AppPassword`: `{ "id": "…", "name": "DAVx⁵ on my phone", "created_at": "…", "last_used_at": "…" }`
(`last_used_at` is updated at most once per hour; `null` = never used)

| Method | Path | Body | Response |
|--------|------|------|----------|
| GET    | `/auth/app-passwords` | – | `AppPassword[]` (newest first) |
| POST   | `/auth/app-passwords` | `{ "name": "…", "current_password": "…" }` | `201 AppPassword` plus `"password": "abcd-efgh-jkmn-pqrs"` – shown only this once |
| DELETE | `/auth/app-passwords/{id}` | – | `204` – clients using it can no longer sign in |

## Calendars

`Calendar`:
```json
{
  "id": "uuid", "name": "Work", "color": "#0B57D0", "description": "",
  "visible": true, "kind": "default",
  "default_reminders": [10], "default_all_day_reminders": [900],
  "created_at": "…", "updated_at": "…"
}
```

`kind` is `default` or `birthdays`. Every user has exactly one **birthday calendar** ("Geburtstage"), which
the server creates automatically and fills from the contacts: for each contact with a birthday there is an
all-day, yearly event (`🎂 Name`; people born on Feb 29 appear on Feb 28 in non-leap years). Changes to
contacts – via the API or CardDAV – are applied immediately. Name, color and visibility can be changed;
deleting, importing as well as creating, editing and deleting single events are rejected with `400`.

| Method  | Path                         | Body              | Response       |
|---------|------------------------------|-------------------|----------------|
| GET     | `/calendars`                 | –                 | `Calendar[]`   |
| POST    | `/calendars`                 | `CalendarInput`   | `201 Calendar` |
| GET     | `/calendars/{id}`            | –                 | `Calendar`     |
| PUT     | `/calendars/{id}`            | `CalendarInput`   | `Calendar`     |
| DELETE  | `/calendars/{id}`            | –                 | `204` (incl. all events) |
| GET     | `/calendars/{id}/events`     | –                 | `Event[]` (series not expanded) |
| GET     | `/calendars/{id}/export`     | –                 | `text/calendar` (.ics) |
| POST    | `/calendars/{id}/import?timezone=Europe/Berlin` | .ics file as body (`text/calendar`) | `{ "imported": 12, "skipped": [] }` |

`CalendarInput`: `{ "name": "Work", "color": "#0B57D0", "description": "", "visible": true, "default_reminders": [10], "default_all_day_reminders": [900] }`
(everything except `name` optional). If `default_reminders` or `default_all_day_reminders` is missing, the existing
default reminders are kept (see [Reminders](#reminders)). New calendars – including the birthday calendar –
remind 15 minutes before by default (`[15]`), or for all-day events on the day itself at 09:00 (`[-540]`).

`/calendars/{id}/events` returns all events of a calendar as `Event` – series as one entry with `rrule` and
`exdates`. Intended for clients that sync themselves (the Android app uses it to transfer into the Android calendar).

On export, each event contains its reminders (its own or the calendar default) as `VALARM`; on import,
relative `TRIGGER`s are taken over. Events without `VALARM` keep their reminders when imported again.

On import, events with the same `UID` are updated instead of duplicated. `timezone` applies to
times without `TZID` ("floating time"). Modified single occurrences (`RECURRENCE-ID`) are created as
separate events and excluded from the series.

## Events

### `GET /events?start=…&end=…[&calendar_ids=a,b][&q=search text]` → `Occurrence[]`

Returns all events that touch the range `[start, end)` (max. 800 days). Recurring
events are **expanded into single occurrences on the server** – the app does not need to evaluate RRULEs.

```json
{
  "id": "6d1…_1795424400",
  "event_id": "6d1…",
  "calendar_id": "…",
  "title": "Weekly meeting",
  "description": "",
  "location": "Room 3",
  "start": "2026-11-30T08:00:00Z",
  "end": "2026-11-30T09:00:00Z",
  "all_day": false,
  "timezone": "Europe/Berlin",
  "recurring": true,
  "rrule": "FREQ=WEEKLY;BYDAY=MO;UNTIL=20261214T225959Z",
  "contact_id": null,
  "read_only": false,
  "reminders": null
}
```
`id` is unique per occurrence; `event_id` is used for editing/deleting.
`reminders` are the event's reminders as in `Event` (`null` = calendar default).
Hiding via `Calendar.visible` is done on the client.

Birthdays have `read_only: true` and the contact's ID in `contact_id`. If the birth year is known, the
occurrence's title contains the age, e.g. `🎂 Erika Mustermann (62)`. `Event` contains the same two fields.

### `POST /events` → `201 Event`
```json
{
  "calendar_id": "…",
  "title": "Dentist",
  "description": "",
  "location": "",
  "start": "2026-11-26T14:30:00+01:00",
  "end": "2026-11-26T15:00:00+01:00",
  "all_day": false,
  "timezone": "Europe/Berlin",
  "rrule": null,
  "exdates": [],
  "reminders": [10, 1440]
}
```
`description`, `location`, `all_day`, `timezone` (default `UTC`), `rrule`, `exdates`, `reminders` are optional.

`reminders`: list of reminders in minutes before the start (see [Reminders](#reminders)). `null` means
"the calendar's default reminders", `[]` "no reminder". **If the field is missing, the existing
reminders stay unchanged** – new events then use the calendar default. This way, clients without
reminder support (e.g. the sync with the Android calendar) don't overwrite anything.

`Event` (series or single event) corresponds to the input plus `id`, `uid`, `created_at`, `updated_at`.

### `GET /events/{id}` → `Event`
### `PUT /events/{id}` → `Event`  (full body as with POST; for series, changes the whole series)
### `DELETE /events/{id}` → `204`  (for series: the whole series)

### `POST /events/{id}/exdates` → `Event`
Removes a single occurrence from a series:
```json
{ "occurrence_start": "2026-12-07T08:00:00Z" }
```
`occurrence_start` is the occurrence's `start` from `GET /events`.

## Reminders

A reminder is a number of minutes before the start (at most 5 per event, at most 4 weeks = 40320 minutes).
For **all-day** events it refers to midnight in the recipient's time zone; negative values are
a time on the day itself – like `TRIGGER` in iCalendar:

| Value  | Timed event          | All-day event            |
|--------|----------------------|--------------------------|
| `0`    | at the start         | on the day at 00:00      |
| `10`   | 10 minutes before    | the day before at 23:50  |
| `900`  | 15 hours before      | the day before at 09:00  |
| `-540` | invalid              | on the day itself at 09:00 |

For events without their own reminders (`reminders: null`, e.g. all birthdays), the default reminders of
their calendar apply: `default_reminders` for timed events, `default_all_day_reminders` for all-day ones.
The presets are 15 minutes before and on the day itself at 09:00 – so every event has a reminder as long as
it is not changed on the event or in the calendar.

The Android app schedules reminders itself (from its local cache). For browsers, the server sends them via
**Web Push**:

| Method  | Path                    | Body | Response |
|---------|-------------------------|------|---------|
| GET     | `/push/public-key`      | –    | `{ "public_key": "B…" }` (VAPID, Base64url, no sign-in required) |
| POST    | `/push/subscriptions`   | `{ "endpoint": "https://…", "keys": { "p256dh": "…", "auth": "…" }, "timezone": "Europe/Berlin" }` | `204` |
| DELETE  | `/push/subscriptions`   | `{ "endpoint": "https://…" }` | `204` |
| POST    | `/push/test`            | `{ "endpoint": "https://…" }` (optional, otherwise all browsers of the account) | `{ "sent": 1 }` |

`endpoint` and `keys` come from `PushSubscription.toJSON()`; `timezone` determines the start of all-day events.
An endpoint always belongs to the account that subscribed last. The server checks for due reminders every 30
seconds and sends them encrypted (RFC 8291) as JSON:

```json
{
  "type": "reminder", "tag": "<event_id>_<start>_<minutes>", "event_id": "…", "title": "Dentist",
  "location": "", "start": "2026-11-26T13:30:00Z", "end": "2026-11-26T14:00:00Z", "all_day": false, "minutes": 10
}
```
Open tasks with a due date remind once: at the due time, all-day ones on the due date at 09:00 in
the subscription's time zone. Completed tasks and tasks without a date don't remind:

```json
{
  "type": "task", "tag": "task_<task_id>_<due>", "task_id": "…", "list_id": "…", "title": "File taxes",
  "notes": "", "due": "2026-11-26T00:00:00Z", "all_day": true
}
```
Expired subscriptions (response `404`/`410` from the push service) are removed automatically.

---

## Tasks

Tasks belong to task lists. Clients show tasks with a due date in the calendar, tasks without a
date only in the task list. Every user has at least one list: if none exists, `GET /task-lists` creates the
list "Meine Aufgaben" (my tasks); the last list cannot be deleted.

### `GET /task-lists` → `TaskList[]`
```json
[{ "id": "…", "name": "Meine Aufgaben", "color": "#1A73E8", "visible": true, "open_count": 3,
   "created_at": "…", "updated_at": "…" }]
```
`visible`: show this list's tasks in the calendar. `open_count`: number of open tasks.

### `POST /task-lists` → `201 TaskList`  ·  `PUT /task-lists/{id}` → `TaskList`
Body `{ "name": "Shopping", "color": "#0B57D0", "visible": true }`. `color` and `visible` are optional; when
editing, missing values are kept.

### `DELETE /task-lists/{id}` → `204`  (deletes all contained tasks; `400` for the last list)

### `POST /task-lists/{id}/clear-completed` → `{ "deleted": 4 }`
Deletes all completed tasks of the list.

### `GET /tasks[?list_id=…]` → `Task[]`
All of the user's tasks: open before completed, then by due date (without date last).
```json
[{ "id": "…", "list_id": "…", "parent_id": null, "title": "File taxes", "notes": "", "due": "2026-11-25T00:00:00Z",
   "all_day": true, "completed": false, "completed_at": null, "created_at": "…", "updated_at": "…" }]
```
`due` follows the events' time model: all-day as midnight UTC of the day, otherwise a point in time in UTC;
`null` = without date (then `all_day` is always `true`).

`parent_id`: parent task if this is a **subtask**, otherwise `null`. As in Google Tasks, subtasks are
only one level deep and only within one list.

### `POST /tasks` → `201 Task`  ·  `PUT /tasks/{id}` → `Task`
```json
{ "list_id": "…", "title": "Call the dentist", "notes": "Make an appointment",
  "due": "2026-11-24T08:30:00Z", "all_day": false, "completed": false }
```
`list_id` and `title` (1–500 characters) are required. `all_day` defaults to `true`. If `completed` is missing,
the status is kept (new tasks are open). With `list_id` a task can be moved to another list;
its subtasks move along.

`parent_id` makes the task a subtask of a top-level task in the same list, `null` makes it a top-level task
again; if the field is missing, the assignment is kept. `400` if the parent task is itself a
subtask or the task has subtasks of its own. If a subtask changes its list, it becomes a
top-level task.

### `PUT /tasks/{id}/completed` → `Task`
Body `{ "completed": true }` – check off or reopen. `completed_at` is kept as long as the task
stays completed. Completing a parent task also checks off its open subtasks; reopening a
subtask also reopens its parent task. The response only contains the given task – clients apply these
rules themselves or reload the tasks. The same applies when `completed` changes via `PUT /tasks/{id}`.

### `GET /tasks/{id}` → `Task`  ·  `DELETE /tasks/{id}` → `204`  (also deletes the subtasks)

## Address books

### `GET /addressbooks` → `AddressBook[]`
On the first call, creates an address book "Kontakte" (contacts) if none exists yet.
```json
{ "id": "…", "name": "Kontakte", "description": "", "contact_count": 42, "created_at": "…", "updated_at": "…" }
```

### `POST /addressbooks` → `201 AddressBook`  ·  `PUT /addressbooks/{id}` → `AddressBook`
```json
{ "name": "Work", "description": "" }
```

### `DELETE /addressbooks/{id}` → `204`  (deletes all contained contacts)

### `GET /addressbooks/{id}/export` → `text/vcard`
All contacts as one .vcf file.

### `POST /addressbooks/{id}/import` (body: .vcf text, `Content-Type: text/vcard`)
Reads any number of cards (vCard 2.1, 3.0 and 4.0). vCard 2.1 (quoted-printable, character sets) is converted to 3.0.
Contacts with the same UID are updated; cards without a UID get one derived from their content,
so importing the same file again does not create duplicates.
```json
{ "imported": 120, "created": 118, "updated": 2, "unchanged": 0, "skipped": [] }
```

## Contacts

Contacts are stored internally as complete vCards. The API returns the common fields in structured form;
on save, only the changed fields in the vCard are replaced – photos, groups and fields of other
programs are kept.

### `GET /contacts[?addressbook_id=…][&q=search text]` → `Contact[]`
Without photos (see `has_photo`), sorted by display name. Contact groups are not returned.

### `GET /contacts/{id}` → `Contact` (incl. `photo` as a data: URL)

### `POST /contacts` → `201 Contact`  ·  `PUT /contacts/{id}` → `Contact`
```json
{
  "addressbook_id": "…",
  "full_name": "",
  "prefix": "Dr.", "given_name": "Erika", "additional_names": "", "family_name": "Mustermann", "suffix": "",
  "nickname": "",
  "organization": "Example Ltd.", "department": "Sales", "title": "Head of sales",
  "phones": [{ "type": "mobile", "value": "+49 171 1234567" }],
  "emails": [{ "type": "work", "value": "erika@example.org" }],
  "addresses": [{
    "type": "home", "po_box": "", "extended": "", "street": "Heidestr. 17", "locality": "Köln",
    "region": "", "postal_code": "51147", "country": "Deutschland"
  }],
  "urls": [{ "type": "work", "value": "https://example.org" }],
  "birthday": "1964-08-12",
  "note": "",
  "categories": ["Family", "Club"]
}
```
All fields except `addressbook_id` are optional. `full_name` is always built from the name parts; a value sent
along only applies if all name parts are empty, with company, email or phone as a fallback. `type`: for phones `mobile|home|work|fax|pager|other`, otherwise `home|work|other` –
other values are stored as a custom label. `birthday`: `YYYY-MM-DD` or `--MM-DD` (without year).
If `addressbook_id` changes, the contact is moved.

Optional `photo`: new photo as a data: URL (`data:image/jpeg;base64,…`), `""` removes the photo. If the field is missing (or
`null`), the photo stays unchanged.

`categories`: the contact's categories (vCard `CATEGORIES`, taken over by DAVx⁵ as contact groups), at most 100
with up to 100 characters each. Leading/trailing whitespace and duplicates (case-insensitive) are removed. If the
field is missing (or `null`), the categories stay unchanged. The search (`q`) also finds categories.

`Contact` corresponds to the input plus `id`, `uid`, `etag`, `has_photo`, `photo?`, `created_at`, `updated_at`. `etag`
changes with every change of the contact (also of the photo or via CardDAV) and is suitable for syncing.

### `DELETE /contacts/{id}` → `204`

## Contact categories

Categories only exist on contacts; the list of all categories results from `GET /contacts`. The case of
`name` does not matter. All of the user's address books are affected.

### `POST /contact-categories/rename` → `{ "updated": 3 }`
Body `{ "name": "Friends", "new_name": "Circle of friends" }`. If a contact already has `new_name`, both are merged.

### `POST /contact-categories/delete` → `{ "updated": 3 }`
Body `{ "name": "Friends" }`. Removes the category from all contacts; the contacts are kept.

## Administration (administrators only, otherwise `403`)

### `GET /admin/overview`
```json
{ "version": "1.0.0", "started_at": "…", "database_size": 303904,
  "counts": { "users": 2, "disabled_users": 0, "calendars": 2, "events": 120, "addressbooks": 1, "contacts": 35,
              "tasks": 12, "push_subscriptions": 1 },
  "config": { "bind": "0.0.0.0:8080", "data_dir": "/data", "cors_origins": [], "vapid_subject": "mailto:…" } }
```
`calendars` without birthday calendars, `events` without birthdays, `database_size` in bytes (incl. WAL).

### `GET /admin/settings` · `PUT /admin/settings`
```json
{ "allow_registration": false, "token_ttl_days": 30, "defaults": { "allow_registration": false, "token_ttl_days": 30 } }
```
`PUT` with `{ "allow_registration": false }` and/or `{ "token_ttl_days": 7 }` (1–3650, otherwise `400`) only changes the
given values and responds like `GET`. Stored values take precedence over the environment variables (`defaults`).
The lifetime applies to newly issued tokens.

### `GET /admin/users`
All accounts, oldest first:
```json
[ { "id": "…", "email": "anna@example.com", "name": "Anna", "is_admin": true, "disabled": false,
    "created_at": "…", "last_login_at": "…", "totp_enabled": false, "calendars": 2, "events": 120, "contacts": 35, "tasks": 12, "devices": 1 } ]
```
`last_login_at` is the last sign-in or token refresh (`null` = never), `devices` the number of browsers with
notifications.

### `POST /admin/users` → `201`
`{ "email": "…", "password": "at least 8 characters", "name": "…", "is_admin": false, "language": "de" }` – creates an
account with its initial data in `language` (optional, like for registration), even if registration is closed. Response like an entry from `GET /admin/users`.

### `PUT /admin/users/{id}`
Optional `name`, `email`, `password`, `is_admin`, `disabled`, `reset_totp`; only the given fields are changed.
`reset_totp: true` turns two-factor authentication off, e.g. when the phone with the authenticator app is lost. A new password
invalidates all tokens of the account (except for your own account) and deletes its app passwords and push
subscriptions; disabling it deletes the push subscriptions as well. Disabled accounts (`disabled: true`) can neither
sign in nor sync via CardDAV; their tokens are rejected. Your own account cannot be disabled
or demoted (`400`).

### `POST /admin/users/{id}/logout`
Invalidates all tokens issued so far for the account and deletes its push subscriptions. CardDAV clients sign in
with the password or an app password and are not affected – a new password (`PUT /admin/users/{id}`) ends those as
well. Not for your own account (`400`).

### `DELETE /admin/users/{id}` → `204`
Deletes the account with all calendars, events, address books, contacts and tasks. Not for your own account (`400`).

### `GET /admin/logs?after=<id>` · `DELETE /admin/logs` → `204`
The last 2000 log messages since the server start (same filter as the console, `RUST_LOG`), oldest first. Kept
in memory only. With `after` only entries with a greater `id`, for polling; `started_at` changes with a restart,
after which the ids start again at 1. `DELETE` empties the buffer (the console output is unaffected).
```json
{ "entries": [ { "id": 41, "time": "…", "level": "INFO", "target": "synchaven::routes::admin",
                 "message": "Account 3f2a… created by administrator 9c41…" } ],
  "started_at": "…", "capacity": 2000 }
```
`level` is one of `ERROR`, `WARN`, `INFO`, `DEBUG`, `TRACE`; further fields of an entry are appended to `message` as `key=value`.

### `POST /admin/export` → `application/json`
```json
{ "current_password": "…", "code": "123456", "passphrase": "at least 12 characters" }
```
The whole database (all users incl. password hashes and the two-factor secrets) as the file
`synchaven-export-<date>.json`. Because of that it needs the administrator's password, and with two-factor
authentication on also `code` (or a recovery code) – otherwise `401` with `unauthorized`, `totp_required` or
`totp_invalid`. With `passphrase` (optional for the API, always used by the web UI; shorter than 12 characters →
`400`) the file is encrypted:
```json
{ "format": "synchaven-export-encrypted", "format_version": 1,
  "kdf": { "algorithm": "argon2id", "memory_kib": 65536, "iterations": 3, "parallelism": 1, "salt": "<Base64>" },
  "nonce": "<Base64>", "ciphertext": "<Base64>" }
```
The key is derived with Argon2id, the export JSON is encrypted with AES-256-GCM; format, version and `kdf` are
authenticated along with it. Without `passphrase` it is plaintext – treat that file like the database itself. Events in the
birthday calendar are not included, the import regenerates them from the contacts. Push subscriptions are missing
as well; the web UI re-subscribes a browser with notifications enabled the next time it is opened. Same
format as `synchaven export` on the command line:
```json
{ "format": "synchaven-export", "format_version": 1, "app_version": "1.0.0", "exported_at": "…", "migration": 1,
  "tables": { "users": [ { "id": "…", "email": "…", … } ], "calendars": [ … ], … } }
```

### `POST /admin/import?replace=true` (body: export, `Content-Type: application/json`, max. 512 MB)
An encrypted export needs its passphrase in the header `X-Export-Passphrase`, Base64-encoded (UTF-8); missing or
wrong → `400`. Replaces **all** data of all users in one transaction. Without `replace=true` → `409` as long as the database
contains data; invalid file or inconsistent data → `400`, the database then stays unchanged. Tables and
columns that no longer exist are skipped, as are push subscriptions from older exports. Existing
push subscriptions are deleted. Tokens stay valid as long as the user still exists afterwards.
```json
{ "summary": ["users: 2", "calendars: 3", "events: 120", …, "Geburtstage: 35"], "warnings": ["Spalten von users gibt es nicht mehr, …"] }
```

## CardDAV (DAVx⁵, Thunderbird, iOS)

Independently of the REST API, the server provides address books via CardDAV (RFC 6352). Sign-in via HTTP Basic
with email address and an app password (see above) or – only while two-factor authentication is off – the account
password. Base URL: `https://<server>/dav/` (discovery also via `/.well-known/carddav`). Wrong account passwords count
towards the sign-in limit of `/auth/login`; while it applies, requests get `429` with `Retry-After` (seconds). App
passwords are not limited.

| Path | Content |
|------|--------|
| `/dav/principals/{user_id}/` | Principal with `addressbook-home-set` |
| `/dav/addressbooks/{user_id}/` | all address books |
| `/dav/addressbooks/{user_id}/{addressbook_id}/` | address book (`getctag`, `addressbook-multiget`, `addressbook-query`) |
| `/dav/addressbooks/{user_id}/{addressbook_id}/{name}.vcf` | contact (`GET`, `PUT` with `If-Match`/`If-None-Match`, `DELETE`) |

Address books can also be managed by the client via `MKCOL`, `PROPPATCH` (name, description) and `DELETE`.
Changes via the REST API are immediately visible via CardDAV and vice versa.

---

## Example with curl

```bash
B=http://localhost:8080/api
TOKEN=$(curl -s -X POST $B/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"anna@example.com","password":"secret123"}' | jq -r .token)
curl -s "$B/events?start=2026-11-01T00:00:00Z&end=2026-12-01T00:00:00Z" -H "Authorization: Bearer $TOKEN"

# Import contacts from a .vcf file into the first address book
BOOK=$(curl -s $B/addressbooks -H "Authorization: Bearer $TOKEN" | jq -r '.[0].id')
curl -s -X POST "$B/addressbooks/$BOOK/import" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: text/vcard' --data-binary @contacts.vcf
```
