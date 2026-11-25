# Synchaven

Synchaven is a self-hosted calendar and contact manager with a **Rust backend** (axum + SQLite), a **Vue 3 web UI in
Material 3 design** (Vuetify), a **REST API** for custom clients such as an Android app, and a
**CardDAV server** for syncing contacts with DAVx⁵.

## Features

* Multi-user with registration/sign-in (Argon2 passwords, JWT bearer tokens)
* Multiple colored calendars per user, can be shown/hidden
* All-day, multi-day and timed events
* Recurring events (RFC 5545 RRULE: daily, weekly, monthly, yearly, custom),
  single occurrences can be deleted; correct across daylight saving time changes
* **Reminders** per event and as a default per calendar (preset to 15 minutes before, all-day at 09:00 on the day;
  also for birthdays): as a push notification in the
  browser – even with the tab closed – and as an Android notification in the app
* iCalendar import and export (.ics) – compatible with Google Calendar, Outlook, Thunderbird, Apple
* Views: month, week, day, event list with search
* **Tasks** like in Google Calendar: multiple colored task lists, tasks with a due date (all-day or with a
  time) appear in the calendar and can be checked off and moved via drag & drop there; task panel with
  quick entry, also for tasks without a date; **subtasks** (one level deep; completing a parent task also checks
  off its subtasks); open tasks remind when due (all-day ones at 09:00)
* **Contacts:** multiple address books, search, phone/email/addresses/websites with types and custom labels,
  birthday (also without year), notes, categories (for filtering, renamable; contact groups in DAVx⁵), photos from imported or synced contacts
* **Birthday calendar:** birthdays of all contacts automatically appear as yearly events in a separate
  birthday calendar (with age, if the birth year is known) – also for contacts synced via DAVx⁵
* vCard import and export (.vcf, also via drag & drop) – vCard 2.1, 3.0 and 4.0, e.g. from Android, iPhone,
  Google Contacts, Outlook, Thunderbird; importing again does not create duplicates
* **Sync via CardDAV** with DAVx⁵ (Android), Thunderbird and iOS – in both directions
* Material 3 with a dynamic color scheme from a freely selectable base color, light/dark/system
* **German and English** – the web UI follows the browser language (English for other languages); it can be
  chosen on registration – which also decides the language of the initial calendar, task list and address book –
  and in the settings. The server answers in the language of the request
* Keyboard shortcuts: `M` `W` `T` `L` (view), `H` (today), `←`/`→` (previous/next), `C` (new event), `A` (new task), `/` (search)

## Running with Docker

The quickest way is the prebuilt image (published by the *Docker image* workflow for every `v*` tag) from the GitHub Container Registry: download
`docker-compose.yml` and start it (without a `.env`, the `latest` image is used):

```bash
docker compose up -d
```

Or build the image yourself, then start it:

```bash
./build.sh
docker compose up -d
```

Then open <http://localhost:8080> and register an account. The data (SQLite database and
JWT secret) is stored in the `synchaven-data` volume under `/data`.

Instead of the volume, a host directory can be mounted (`./data:/data`). In that case set `PUID` and
`PGID` to the owner's IDs (`id -u` / `id -g`): on startup the container adjusts the ownership of
`/data` and runs the program with these IDs, never as root. If the container is started with `user:`,
this adjustment is skipped – the directory must then belong to that user.

After code changes: `./build.sh && docker compose up -d` – Compose then replaces the container
with one using the new image.

### Build script

`build.sh` takes no parameters. It builds the image as `<image>:<version>`, where `<version>` comes
from `version.properties` (entry `version=…`) and `<image>` from `SYNCHAVEN_IMAGE` (environment variable
or `.env`, default `ghcr.io/daved09/synchaven`). It writes both as `SYNCHAVEN_IMAGE` and `SYNCHAVEN_VERSION`
to `.env`. `docker compose` reads this file automatically, so Compose always starts the most recently built
version. For your own registry, put e.g. `SYNCHAVEN_IMAGE=registry.example.org/synchaven` into `.env`. For a
new version, just increase `version` in `version.properties`.

The backend reads the same file at compile time via `backend/build.rs` and reports the version at
`GET /api/info` – also with `cargo run` without `build.sh`. The version in `backend/Cargo.toml` is only a
placeholder (`0.0.0`).

Pushing to your own registry:

```bash
docker push registry.example.org/synchaven:1.0.0
```

On another machine, `docker-compose.yml` and a `.env` with `SYNCHAVEN_IMAGE=…` and
`SYNCHAVEN_VERSION=1.0.0` are enough – the image is then pulled from the registry.

Without Compose:

```bash
./build.sh
docker run -d --name synchaven -p 8080:8080 -v synchaven-data:/data ghcr.io/daved09/synchaven:1.0.0
```

### Configuration (environment variables)

| Variable             | Default           | Description |
|----------------------|-------------------|--------------|
| `BIND_ADDR`          | `0.0.0.0:8080`    | Address/port |
| `DATA_DIR`           | `/data`           | Location of `synchaven.db` and `jwt_secret` |
| `ALLOW_REGISTRATION` | `true`            | Allow new accounts (default; can be changed in the admin page) |
| `TOKEN_TTL_DAYS`     | `30`              | Session lifetime (default; can be changed in the admin page) |
| `JWT_SECRET`         | *(automatic)*     | Custom secret (≥ 32 characters) |
| `CORS_ORIGINS`       | `*`               | Allowed browser origins, comma-separated |
| `VAPID_SUBJECT`      | `mailto:synchaven@example.org` | Contact address for push services (`mailto:` or `https:`) |
| `RUST_LOG`           | `synchaven=info`  | Log level |
| `PUID` / `PGID`      | `10001`           | User/group ID for the program and `/data` (Docker only) |

The **administrator** is the first account to be registered. The first account can also be created with
`ALLOW_REGISTRATION=false`. Administrators find the admin page under *Account → Administration*:

* **Overview:** version, uptime, database size, number of users, events, contacts etc. and the
  server configuration from the environment variables
* **Users:** create accounts (also with registration closed), change name/email, reset password,
  grant admin rights, disable and enable accounts, sign out on all devices and delete. A disabled
  account can neither sign in nor sync via CardDAV. Your own account cannot be disabled, deleted
  or demoted – so there is always an administrator left.
* **Settings:** open/close registration and session lifetime. Stored values take precedence over
  `ALLOW_REGISTRATION` and `TOKEN_TTL_DAYS`; as long as nothing is stored, the environment variables apply.
* **Backup:** export and import of the whole database (see below)
* **Log:** the last 2000 log messages since the server start, live, with level filter, search and
  download. Which messages appear is set with `RUST_LOG`, as for the console.

**Note:** When running on the internet, put a reverse proxy with HTTPS in front (e.g. Caddy, Traefik, nginx)
and close registration after creating your own accounts (admin page or
`ALLOW_REGISTRATION=false`).

### Browser notifications

Reminders are sent via Web Push: under *Account → Settings → Notifications* each browser can be
subscribed individually; there is also a test notification. Requirements:

* The web UI must be accessed via **HTTPS** (or `localhost`) – otherwise browsers block notifications.
* The server must be able to reach the browsers' push services (e.g. `fcm.googleapis.com`, `updates.push.services.mozilla.com`,
  `web.push.apple.com`). For some services `VAPID_SUBJECT` should be a real contact address.
* iPhone/iPad (iOS 16.4 or later): first install Synchaven via *Share → Add to Home Screen* and open it from there.

The server's key (VAPID) is stored in `vapid_private_key` in the data directory. If it is lost, the
browsers have to enable notifications again.

### Syncing contacts with DAVx⁵

1. In DAVx⁵, tap **+** and choose **Login with URL and user name**.
2. Base URL `https://<server>/dav/` (the start address works as well), user name = email address,
   password = sign-in password.
3. Create the account and enable the address books in the **CardDAV** tab.

The web UI shows the same details under *Contacts → Sync with DAVx⁵*. Since the password is sent with
every request, the server must be behind HTTPS when accessed over the internet.

### Backup

```bash
docker run --rm -v synchaven-data:/data -v "$PWD":/backup debian:bookworm-slim \
  tar czf /backup/synchaven-backup.tgz -C /data .
```

#### Export and import as JSON

In addition, the whole database (all users, calendars, events, address books, contacts, tasks,
admin settings) can be exported as JSON – mainly intended as a fallback before an update, in case a
migration fails. The events in the birthday calendar are not included; they are regenerated from the contacts
on import. The browsers' push subscriptions are not included either: a browser with notifications enabled
re-subscribes the next time the web UI is opened.

**In the web UI:** as an administrator, *Account → Administration → Backup* has the buttons
*Export database* and *Import database*. The import replaces all data of all users. After a failed
migration: set the old database aside (see below), start the server, register an account (the first
account becomes administrator and also works with `ALLOW_REGISTRATION=false`) and import the export – the
temporary account is replaced by the accounts from the export. If the export does not contain an
administrator yet (older versions), the oldest account becomes one.

**On the command line** – the export also works while the server is running:

```bash
docker exec synchaven synchaven export > synchaven-export.json
```

The import creates an empty database with the schema of the current version and loads the data. Columns or
tables that no longer exist are skipped with a warning, new columns get their default value.
The server must be stopped for this. Procedure after a failed migration:

```bash
docker compose stop
# Set the old database aside (synchaven.db, synchaven.db-wal, synchaven.db-shm)
docker compose run --rm --no-deps --entrypoint sh Synchaven \
  -c 'mkdir -p /data/alt && mv /data/synchaven.db* /data/alt/'
docker compose run --rm --no-deps -v "$PWD":/backup Synchaven import /backup/synchaven-export.json
docker compose up -d
```

If the database already contains data, the import aborts; `import --replace …` deletes it first. The JWT secret and
VAPID key are files in `/data` and not part of the export – if they are kept, sign-ins stay
valid and browsers can re-subscribe to notifications without granting permission again. Without Docker: `DATA_DIR=./data synchaven export file.json` or
`synchaven import file.json`.

## REST API

The full API reference is in [docs/API.md](docs/API.md). Quick overview:

| Area      | Endpoints |
|-----------|-----------|
| Account   | `POST /api/auth/register`, `POST /api/auth/login`, `POST /api/auth/refresh`, `GET/PUT /api/auth/me` |
| Calendars | `GET/POST /api/calendars`, `GET/PUT/DELETE /api/calendars/{id}`, `GET …/export`, `POST …/import` |
| Events    | `GET /api/events?start&end`, `POST /api/events`, `GET/PUT/DELETE /api/events/{id}`, `POST /api/events/{id}/exdates` |
| Tasks     | `GET/POST /api/task-lists`, `PUT/DELETE /api/task-lists/{id}`, `GET/POST /api/tasks`, `GET/PUT/DELETE /api/tasks/{id}`, `PUT /api/tasks/{id}/completed` |
| Address books | `GET/POST /api/addressbooks`, `PUT/DELETE /api/addressbooks/{id}`, `GET …/export`, `POST …/import` |
| Contacts  | `GET /api/contacts[?addressbook_id&q]`, `POST /api/contacts`, `GET/PUT/DELETE /api/contacts/{id}` |
| CardDAV   | `/dav/` (Basic auth, see [docs/API.md](docs/API.md#carddav-davx-thunderbird-ios)) |

The server expands recurring events into single occurrences, so clients don't need their own
RRULE logic when online (the Android app only expands them itself when offline, from `GET /calendars/{id}/events`).

## Android app

`Android/` contains a native app (Kotlin, Jetpack Compose, Material 3) with the same features as the
web UI: sign-in/registration, month, week, day and list views, creating, editing and
deleting events (also single occurrences) and moving them by long-pressing, recurrence, search, managing calendars,
.ics import/export, contacts with address books and .vcf import/export.

**Tasks:** tasks with a due date appear in month, week/day and event list with a check-off icon (completed ones
struck through); tapping opens the details with *Mark as completed*, in the event list the icon checks them off
directly, and in the time grid they can be moved by long-pressing. Task lists are shown and hidden, edited
and created in the drawer; *All tasks* opens the task panel with quick entry and a completed
section, and the create button offers *Event* or *Task*. Also available in local
mode.

**Sync with Android (replaces DAVx⁵):** the app creates an Android account "Synchaven" and itself writes calendars
and contacts into the Android calendar and the system contacts – via its own SyncAdapters and the REST API,
in both directions. Changes in Android's calendar or contacts app are uploaded, changes on
the server are taken over periodically (configurable, hourly by default) and after every change in the app. Enable it
under *Settings → Sync with Android*, separately for calendars and contacts. For this, the password is stored in the
Android account so the sync can sign in again by itself after the token expires.

* Series are transferred with RRULE/EXDATE; a single occurrence modified or cancelled on the device becomes a
  separate event or an EXDATE of the series on the server (as with .ics import).
* The birthday calendar is read-only on the device.
* Contacts of all address books end up in the account; contacts newly created on the device go into the first address book.
  Name, nickname, company, phone, email, addresses, websites, birthday, note and photo are transferred –
  the contacts app only offers these fields for Synchaven contacts.
* Anyone who previously used DAVx⁵ for the same address books should remove the account there, otherwise
  contacts appear twice.

**Reminders:** the app shows reminders for events and due tasks as Android notifications – scheduled from the local
cache, so also offline and in local mode. Turn them on and off under *Settings → Notifications*; from
Android 13 on the app asks for the permission once. Changes from the web UI arrive with the next sync
(when opening the app, at the latest roughly every four hours). No reminders are transferred
to the Android calendar, so they don't appear twice.

**Offline mode:** calendars, all events (series with RRULE/EXDATE), address books and contacts are additionally stored in
a local cache on the device; contact photos are saved when a contact is opened. If the server is not
reachable (on the go, server off), the app shows the stored data – it then computes occurrences of series and the age in the
birthday calendar itself – and a notice above the navigation bar makes the offline state
visible. Events and contacts can also be created, edited, moved and deleted offline, calendars shown and
hidden and the default view changed; likewise tasks can be created, edited, checked off and deleted and
task lists shown/hidden. These changes go into a queue and are transferred in order
as soon as the server is reachable again (on network change, when opening the app, every minute in the foreground
or via *Settings → Offline data → Sync now*); afterwards the app reloads everything. If the server rejects a
change (e.g. because the event has been deleted in the meantime), it is discarded and this is shown. Online only:
creating/deleting calendars, address books and task lists, import/export, profile. Signing out deletes the cache; if only the
token expires, it is kept and pending changes are transferred after signing in again.

**Without a server (local mode):** with *Use without server* on the sign-in screen the app works entirely
without the web UI. Calendars, events, tasks, address books and contacts are then stored only on the device (`filesDir/local/`,
so included in Android backups) – with all features: managing calendars, task lists and address books, series,
birthday calendar from the contacts, .ics and .vcf import/export and sync with the Android calendar
and the system contacts (account "Synchaven (lokal)"). Technically, `data/local/LocalApi.kt` replaces the REST API: it
stores data in the same local storage as the offline cache and validates input like the server; iCalendar, vCard and
birthdays are ports from `backend/src`. On vCard import, only the fields the app knows are kept.
*Settings → Connect to server* switches to sign-in; the local data stays on the device and is back the
next time *Use without server* is chosen – it is not transferred to a server (use export and import for that; tasks are not
included).

On first start, enter the server address (e.g. `http://192.168.1.10:8080`) or choose *Use without server*.
Unencrypted HTTP is allowed for servers on the home network; user-installed certificate authorities are
accepted.

```bash
cd Android
./gradlew assembleRelease          # → app/build/outputs/apk/release/SyncHaven.apk
./gradlew testDebugUnitTest        # unit tests for date, recurrence, contact, sync, offline and file format logic
```

The app takes its version from `version.properties` as well: `versionName` is the version itself, `versionCode`
is derived from it (`1.2.3` → `10203`, so MINOR and PATCH must stay below 100).

`assembleRelease` signs the APK when these environment variables are set, otherwise it stays unsigned:
`ANDROID_KEYSTORE_FILE` (path to the keystore), `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.

### Releases

Pushing a tag `v<version>` (matching `version.properties`) builds and publishes everything at once:
the Docker image to `ghcr.io/daved09/synchaven` and the signed `SyncHaven.apk` as a download on the GitHub release.

```bash
git tag v1.0.1 && git push origin v1.0.1
```

The Android workflow needs the repository secrets `ANDROID_KEYSTORE_BASE64` (the keystore as `base64 -w0`),
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`. Always use the same keystore –
Android only installs an update if it is signed with the same key as the installed app.

Structure: `data/` (Retrofit API, repositories, DataStore for session and settings), `data/offline/` (local
cache per account, queue of changes made offline, sync with the server), `data/local/` (local mode
without a server: LocalApi, iCalendar, vCard, birthdays), `ui/<area>/`
(Compose screens with one ViewModel each), `util/` (port of the helper logic from `frontend/src/utils`),
`sync/` (Android account, SyncAdapters for CalendarContract and ContactsContract).

## Development

Requirements: Rust (stable), Node.js ≥ 20.

```bash
# Backend (http://localhost:8080)
cd backend && cargo run

# Frontend with hot reload (http://localhost:5173, proxies /api to the backend)
cd frontend && npm install && npm run dev
```

Tests and checks:

```bash
cd backend && cargo test
cd frontend && npm run typecheck && npm run build
```

### Translations

Every text exists once per language, in JSON files with the same keys:

* Web UI: `frontend/src/locales/de.json`, `en.json` (vue-i18n, placeholders `{name}`, plural forms
  separated by ` | `). Used as `$t('key')` in templates and `t('key')` in code.
* Backend: `backend/locales/de.json`, `en.json` (rust-i18n, placeholders `%{name}`). Used as `tr!("key")`;
  the language comes from the request's `Accept-Language` header, see `src/i18n.rs`.

`npm run build` (or `npm run check:i18n`) and `cargo test` fail if a key is missing in a language, the
placeholders differ, or a key is unknown or unused. A new language needs one more file on each side plus an entry
in `LANGUAGES` (`frontend/src/i18n.ts`) and in `Lang` (`backend/src/i18n.rs`).

## Project structure

```
backend/            Rust (axum, sqlx/SQLite, rrule, jsonwebtoken, argon2, quick-xml)
  migrations/       SQL schema (applied automatically on startup)
  locales/          Texts of API messages and default names per language
  src/routes/       REST endpoints (auth, calendars, events, ical, contacts)
  src/recurrence.rs Expansion of recurring events
  src/ical.rs       iCalendar import/export
  src/vcard.rs      Reading/writing vCards (incl. 2.1 import), lossless editing
  src/contacts.rs   Storage of address books and contacts
  src/birthdays.rs  Birthday calendar from the contacts
  src/dav.rs        CardDAV server for DAVx⁵ & co.
  test/             Unit tests, mirroring src/ (test/vcard.rs tests src/vcard.rs)
frontend/           Vue 3 + Vuetify 3 (Material 3) + Pinia
  src/components/   Month, week/day and list views, contact details, dialogs
  src/views/        Sign-in, calendar, contacts
  src/plugins/      Vuetify setup and M3 color scheme generator
  src/locales/      Texts of the web UI per language
Android/            Android app (Kotlin, Jetpack Compose, Material 3, MVVM)
docs/API.md         API reference
Dockerfile          Multi-stage build → slim Debian image
```

## License

Synchaven is licensed under the [GNU Affero General Public License v3.0](LICENSE) (`AGPL-3.0-only`).
If you run a modified version as a network service, you must make its source code available to its users.
