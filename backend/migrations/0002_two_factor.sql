-- Two-factor authentication with TOTP (RFC 6238) and app passwords for CardDAV.

-- Base32 secret of the authenticator app; NULL = two-factor authentication is off
ALTER TABLE users ADD COLUMN totp_secret TEXT;
-- Secret during setup, until the first code confirms it; NULL = no setup in progress
ALTER TABLE users ADD COLUMN totp_pending_secret TEXT;
-- Time step (Unix seconds / 30) of the last accepted code, so a code cannot be used twice
ALTER TABLE users ADD COLUMN totp_last_step INTEGER;
-- Unused recovery codes as a JSON array of SHA-256 hashes (hex); each code works once
ALTER TABLE users ADD COLUMN totp_recovery_codes TEXT NOT NULL DEFAULT '[]';

-- App passwords: random passwords for sync clients (CardDAV) that cannot enter a code.
-- With two-factor authentication on, CardDAV only accepts these, not the account password.
CREATE TABLE app_passwords (
    id            TEXT PRIMARY KEY NOT NULL,
    user_id       TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name          TEXT NOT NULL,
    -- SHA-256 (hex) of the password; it is random and long, so a slow hash is not needed
    password_hash TEXT NOT NULL UNIQUE,
    created_at    TEXT NOT NULL,
    -- Last use (format like created_at, updated at most once per hour); NULL = never
    last_used_at  TEXT
);
CREATE INDEX idx_app_passwords_user ON app_passwords(user_id);
