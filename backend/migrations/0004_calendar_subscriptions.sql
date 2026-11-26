-- Subscribed calendars (kind 'subscription'): the server fetches an iCalendar file (e.g. public holidays) from a URL
-- at regular intervals and replaces the calendar's events with its content. Their events are read-only.

-- Address of the .ics file (http/https; webcal:// is stored as https://); NULL for other calendars
ALTER TABLE calendars ADD COLUMN source_url TEXT;
-- Last successful refresh (format like created_at); NULL = never
ALTER TABLE calendars ADD COLUMN synced_at TEXT;
-- Last attempt, successful or not; decides when the next one is due
ALTER TABLE calendars ADD COLUMN sync_attempted_at TEXT;
-- Error of the last attempt in the account's language; NULL = it succeeded
ALTER TABLE calendars ADD COLUMN sync_error TEXT;

-- Event comes from a subscribed calendar: read-only, replaced on every refresh
ALTER TABLE events ADD COLUMN subscribed INTEGER NOT NULL DEFAULT 0;
