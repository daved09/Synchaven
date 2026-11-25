CREATE TABLE users (
    id            TEXT PRIMARY KEY NOT NULL,
    email         TEXT NOT NULL UNIQUE COLLATE NOCASE,
    name          TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    -- The calendar's default view (month, week, day, list); applies to the web UI and the app
    default_view  TEXT NOT NULL DEFAULT 'month',
    -- Administrator: initially the first registered account, further ones are granted in the admin page
    is_admin      INTEGER NOT NULL DEFAULT 0,
    created_at    TEXT NOT NULL,
    updated_at    TEXT NOT NULL,
    -- Disabled accounts can neither sign in nor sync via CardDAV; their tokens are rejected.
    disabled      INTEGER NOT NULL DEFAULT 0,
    -- Tokens issued before this point in time (Unix seconds) are invalid
    -- ("sign out everywhere", password reset by the administrator). NULL = all valid.
    tokens_valid_after INTEGER,
    -- Last sign-in or token refresh (format like created_at); NULL = never
    last_login_at TEXT,
    -- Language for texts the server stores for the user (default names, birthday events):
    -- 'en' or 'de', taken from the registration or the settings. The default 'de' applies to
    -- imports of older exports, whose accounts were created with German texts.
    language      TEXT NOT NULL DEFAULT 'de'
);

CREATE TABLE calendars (
    id          TEXT PRIMARY KEY NOT NULL,
    user_id     TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name        TEXT NOT NULL,
    color       TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    visible     INTEGER NOT NULL DEFAULT 1,
    -- Kind of calendar: 'default' or 'birthdays' (generated automatically from the contacts, read-only)
    kind        TEXT NOT NULL DEFAULT 'default',
    -- Default reminders, separately for timed events and all-day events:
    -- 15 minutes before, or on the day itself at 09:00 (format like events.reminders).
    default_reminders         TEXT NOT NULL DEFAULT '[15]',
    default_all_day_reminders TEXT NOT NULL DEFAULT '[-540]',
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL
);
CREATE INDEX idx_calendars_user ON calendars(user_id);
CREATE UNIQUE INDEX idx_calendars_birthdays ON calendars(user_id) WHERE kind = 'birthdays';

-- Timestamps are stored as UTC in the format YYYY-MM-DDTHH:MM:SSZ,
-- so lexicographic comparisons are correct.
CREATE TABLE events (
    id          TEXT PRIMARY KEY NOT NULL,
    calendar_id TEXT NOT NULL REFERENCES calendars(id) ON DELETE CASCADE,
    uid         TEXT NOT NULL,
    title       TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    location    TEXT NOT NULL DEFAULT '',
    start_at    TEXT NOT NULL,
    end_at      TEXT NOT NULL,
    all_day     INTEGER NOT NULL DEFAULT 0,
    timezone    TEXT NOT NULL DEFAULT 'UTC',
    rrule       TEXT,
    exdates     TEXT NOT NULL DEFAULT '[]',
    -- Birthday events belong to a contact and disappear with it.
    contact_id  TEXT REFERENCES contacts(id) ON DELETE CASCADE,
    -- Birth year (if known) for showing the age
    birth_year  INTEGER,
    -- Reminders: minutes before the start as a JSON list, e.g. [10, 1440].
    -- Negative values only for all-day events: time on the day itself (-540 = 09:00).
    -- NULL = use the calendar's default reminders.
    reminders   TEXT,
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL
);
CREATE INDEX idx_events_calendar ON events(calendar_id);
CREATE INDEX idx_events_range ON events(start_at, end_at);
CREATE UNIQUE INDEX idx_events_uid ON events(calendar_id, uid);
CREATE INDEX idx_events_contact ON events(contact_id);

CREATE TABLE addressbooks (
    id          TEXT PRIMARY KEY NOT NULL,
    user_id     TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name        TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    -- Incremented on every change (CardDAV getctag)
    revision    INTEGER NOT NULL DEFAULT 0,
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL
);
CREATE INDEX idx_addressbooks_user ON addressbooks(user_id);

-- The vCard text is the source of truth; the other columns are derived from it.
CREATE TABLE contacts (
    id             TEXT PRIMARY KEY NOT NULL,
    addressbook_id TEXT NOT NULL REFERENCES addressbooks(id) ON DELETE CASCADE,
    uid            TEXT NOT NULL,
    -- Last path segment of the CardDAV resource, e.g. "<uid>.vcf"
    href           TEXT NOT NULL,
    etag           TEXT NOT NULL,
    vcard          TEXT NOT NULL,
    kind           TEXT NOT NULL DEFAULT 'individual',
    full_name      TEXT NOT NULL,
    search         TEXT NOT NULL DEFAULT '',
    created_at     TEXT NOT NULL,
    updated_at     TEXT NOT NULL
);
CREATE UNIQUE INDEX idx_contacts_href ON contacts(addressbook_id, href);
CREATE UNIQUE INDEX idx_contacts_uid ON contacts(addressbook_id, uid);

-- Task lists (as in Google Tasks); tasks with a due date appear in the calendar.
CREATE TABLE task_lists (
    id         TEXT PRIMARY KEY NOT NULL,
    user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name       TEXT NOT NULL,
    color      TEXT NOT NULL,
    visible    INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE INDEX idx_task_lists_user ON task_lists(user_id);

CREATE TABLE tasks (
    id           TEXT PRIMARY KEY NOT NULL,
    list_id      TEXT NOT NULL REFERENCES task_lists(id) ON DELETE CASCADE,
    title        TEXT NOT NULL,
    notes        TEXT NOT NULL DEFAULT '',
    -- Due date (UTC, format like events.start_at); NULL = without date, doesn't appear in the calendar.
    -- All-day (all_day = 1): midnight UTC of the day, as with all-day events.
    due_at       TEXT,
    all_day      INTEGER NOT NULL DEFAULT 1,
    -- Completion time; NULL = open
    completed_at TEXT,
    -- Subtasks (one level deep, as in Google Tasks): parent task in the same list.
    -- If the parent task is deleted, its subtasks disappear with it.
    parent_id    TEXT REFERENCES tasks(id) ON DELETE CASCADE,
    created_at   TEXT NOT NULL,
    updated_at   TEXT NOT NULL
);
CREATE INDEX idx_tasks_list ON tasks(list_id);
CREATE INDEX idx_tasks_due ON tasks(due_at);
CREATE INDEX idx_tasks_parent ON tasks(parent_id);

-- Web Push subscriptions (one entry per browser)
CREATE TABLE push_subscriptions (
    id         TEXT PRIMARY KEY NOT NULL,
    user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    endpoint   TEXT NOT NULL UNIQUE,
    p256dh     TEXT NOT NULL,
    auth       TEXT NOT NULL,
    -- The browser's time zone, needed for reminders for all-day events
    timezone   TEXT NOT NULL DEFAULT 'UTC',
    user_agent TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE INDEX idx_push_subscriptions_user ON push_subscriptions(user_id);

-- Small key-value store for server state (e.g. progress of the reminder service)
CREATE TABLE server_state (
    key   TEXT PRIMARY KEY NOT NULL,
    value TEXT NOT NULL
);

-- Admin settings. If an entry is missing, the default from the environment variable applies.
CREATE TABLE settings (
    key   TEXT PRIMARY KEY NOT NULL,
    value TEXT NOT NULL
);
