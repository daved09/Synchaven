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
    created_at   TEXT NOT NULL,
    updated_at   TEXT NOT NULL
);
CREATE INDEX idx_tasks_list ON tasks(list_id);
CREATE INDEX idx_tasks_due ON tasks(due_at);
