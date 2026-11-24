CREATE TABLE users (
    id            TEXT PRIMARY KEY NOT NULL,
    email         TEXT NOT NULL UNIQUE COLLATE NOCASE,
    name          TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    -- Standardansicht des Kalenders (month, week, day, list); gilt für Weboberfläche und App
    default_view  TEXT NOT NULL DEFAULT 'month',
    -- Administrator: das erste registrierte Konto
    is_admin      INTEGER NOT NULL DEFAULT 0,
    created_at    TEXT NOT NULL,
    updated_at    TEXT NOT NULL
);

CREATE TABLE calendars (
    id          TEXT PRIMARY KEY NOT NULL,
    user_id     TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name        TEXT NOT NULL,
    color       TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    visible     INTEGER NOT NULL DEFAULT 1,
    -- Art des Kalenders: 'default' oder 'birthdays' (automatisch aus den Kontakten erzeugt, schreibgeschützt)
    kind        TEXT NOT NULL DEFAULT 'default',
    -- Standard-Erinnerungen, getrennt für Termine mit Uhrzeit und ganztägige Termine:
    -- 15 Minuten vorher bzw. am Tag selbst um 09:00 (Format wie events.reminders).
    default_reminders         TEXT NOT NULL DEFAULT '[15]',
    default_all_day_reminders TEXT NOT NULL DEFAULT '[-540]',
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL
);
CREATE INDEX idx_calendars_user ON calendars(user_id);
CREATE UNIQUE INDEX idx_calendars_birthdays ON calendars(user_id) WHERE kind = 'birthdays';

-- Zeitstempel werden als UTC im Format YYYY-MM-DDTHH:MM:SSZ gespeichert,
-- damit lexikografische Vergleiche korrekt sind.
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
    -- Geburtstagstermine gehören zu einem Kontakt und verschwinden mit ihm.
    contact_id  TEXT REFERENCES contacts(id) ON DELETE CASCADE,
    -- Geburtsjahr (falls bekannt) für die Altersangabe
    birth_year  INTEGER,
    -- Erinnerungen: Minuten vor Beginn als JSON-Liste, z. B. [10, 1440].
    -- Negative Werte nur bei ganztägigen Terminen: Uhrzeit am Tag selbst (-540 = 09:00).
    -- NULL = Standard-Erinnerungen des Kalenders verwenden.
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
    -- Wird bei jeder Änderung erhöht (CardDAV getctag)
    revision    INTEGER NOT NULL DEFAULT 0,
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL
);
CREATE INDEX idx_addressbooks_user ON addressbooks(user_id);

-- Der vCard-Text ist die maßgebliche Quelle; die übrigen Spalten sind daraus abgeleitet.
CREATE TABLE contacts (
    id             TEXT PRIMARY KEY NOT NULL,
    addressbook_id TEXT NOT NULL REFERENCES addressbooks(id) ON DELETE CASCADE,
    uid            TEXT NOT NULL,
    -- Letztes Pfadsegment der CardDAV-Ressource, z. B. "<uid>.vcf"
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

-- Aufgabenlisten (wie in Google Tasks); Aufgaben mit Fälligkeit erscheinen im Kalender.
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
    -- Fälligkeit (UTC, Format wie events.start_at); NULL = ohne Datum, erscheint nicht im Kalender.
    -- Ganztägig (all_day = 1): Mitternacht UTC des Tages, wie bei ganztägigen Terminen.
    due_at       TEXT,
    all_day      INTEGER NOT NULL DEFAULT 1,
    -- Zeitpunkt der Erledigung; NULL = offen
    completed_at TEXT,
    -- Unteraufgaben (wie in Google Tasks eine Ebene tief): übergeordnete Aufgabe derselben Liste.
    -- Wird die Hauptaufgabe gelöscht, verschwinden ihre Unteraufgaben mit.
    parent_id    TEXT REFERENCES tasks(id) ON DELETE CASCADE,
    created_at   TEXT NOT NULL,
    updated_at   TEXT NOT NULL
);
CREATE INDEX idx_tasks_list ON tasks(list_id);
CREATE INDEX idx_tasks_due ON tasks(due_at);
CREATE INDEX idx_tasks_parent ON tasks(parent_id);

-- Web-Push-Abonnements (ein Eintrag pro Browser)
CREATE TABLE push_subscriptions (
    id         TEXT PRIMARY KEY NOT NULL,
    user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    endpoint   TEXT NOT NULL UNIQUE,
    p256dh     TEXT NOT NULL,
    auth       TEXT NOT NULL,
    -- Zeitzone des Browsers, nötig für Erinnerungen an ganztägige Termine
    timezone   TEXT NOT NULL DEFAULT 'UTC',
    user_agent TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE INDEX idx_push_subscriptions_user ON push_subscriptions(user_id);

-- Kleine Schlüssel-Wert-Ablage für den Serverzustand (z. B. Fortschritt des Erinnerungsdienstes)
CREATE TABLE server_state (
    key   TEXT PRIMARY KEY NOT NULL,
    value TEXT NOT NULL
);
