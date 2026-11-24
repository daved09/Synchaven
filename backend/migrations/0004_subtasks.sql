-- Unteraufgaben (wie in Google Tasks eine Ebene tief): parent_id zeigt auf die übergeordnete Aufgabe derselben Liste.
-- Wird die Hauptaufgabe gelöscht, verschwinden ihre Unteraufgaben mit.
ALTER TABLE tasks ADD COLUMN parent_id TEXT REFERENCES tasks(id) ON DELETE CASCADE;
CREATE INDEX idx_tasks_parent ON tasks(parent_id);
