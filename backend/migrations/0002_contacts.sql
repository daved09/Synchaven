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
