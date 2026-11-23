-- Erinnerungen: Minuten vor Beginn als JSON-Liste, z. B. [10, 1440].
-- Negative Werte nur bei ganztägigen Terminen: Uhrzeit am Tag selbst (-540 = 09:00).
-- NULL = Standard-Erinnerungen des Kalenders verwenden.
ALTER TABLE events ADD COLUMN reminders TEXT;

-- Standard-Erinnerungen eines Kalenders, getrennt für Termine mit Uhrzeit und ganztägige Termine.
-- Voreinstellung (auch für bestehende Kalender): 15 Minuten vorher bzw. am Tag selbst um 09:00.
ALTER TABLE calendars ADD COLUMN default_reminders TEXT NOT NULL DEFAULT '[15]';
ALTER TABLE calendars ADD COLUMN default_all_day_reminders TEXT NOT NULL DEFAULT '[-540]';

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
