-- Art des Kalenders: 'default' oder 'birthdays' (automatisch aus den Kontakten erzeugt, schreibgeschützt)
ALTER TABLE calendars ADD COLUMN kind TEXT NOT NULL DEFAULT 'default';
CREATE UNIQUE INDEX idx_calendars_birthdays ON calendars(user_id) WHERE kind = 'birthdays';

-- Geburtstagstermine gehören zu einem Kontakt und verschwinden mit ihm.
ALTER TABLE events ADD COLUMN contact_id TEXT REFERENCES contacts(id) ON DELETE CASCADE;
-- Geburtsjahr (falls bekannt) für die Altersangabe
ALTER TABLE events ADD COLUMN birth_year INTEGER;
CREATE INDEX idx_events_contact ON events(contact_id);
