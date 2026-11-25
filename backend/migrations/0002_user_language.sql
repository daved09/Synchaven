-- Language for texts the server stores for the user (default names, birthday events):
-- 'en' or 'de', taken from the Accept-Language header on registration and sign-in.
-- Existing accounts were created with German texts.
ALTER TABLE users ADD COLUMN language TEXT NOT NULL DEFAULT 'de';
