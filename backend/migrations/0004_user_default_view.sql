-- Standardansicht des Kalenders (month, week, day, list); gilt für Weboberfläche und App
ALTER TABLE users ADD COLUMN default_view TEXT NOT NULL DEFAULT 'month';
