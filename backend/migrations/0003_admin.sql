-- Administratoren stehen in der Datenbank: Das erste registrierte Konto wird Administrator.
ALTER TABLE users ADD COLUMN is_admin INTEGER NOT NULL DEFAULT 0;

-- Bestehende Installationen: Das älteste Konto wird Administrator.
UPDATE users SET is_admin = 1
WHERE id = (SELECT id FROM users ORDER BY created_at, rowid LIMIT 1);
