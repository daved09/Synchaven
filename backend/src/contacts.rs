//! Storage of address books and contacts – shared by the REST API and CardDAV.

use sha2::{Digest, Sha256};
use sqlx::{FromRow, SqliteConnection, SqlitePool};

use crate::{
    error::{AppError, AppResult},
    time,
    vcard::{self, Card},
};

#[derive(Debug, Clone, FromRow)]
pub struct AddressBookRow {
    pub id: String,
    #[allow(dead_code)]
    pub user_id: String,
    pub name: String,
    pub description: String,
    pub revision: i64,
    pub created_at: String,
    pub updated_at: String,
}

#[derive(Debug, Clone, FromRow)]
pub struct ContactRow {
    pub id: String,
    pub addressbook_id: String,
    pub uid: String,
    pub href: String,
    pub etag: String,
    pub vcard: String,
    #[allow(dead_code)]
    pub kind: String,
    #[allow(dead_code)]
    pub full_name: String,
    #[allow(dead_code)]
    pub search: String,
    pub created_at: String,
    pub updated_at: String,
}


pub fn etag_of(vcard: &str) -> String {
    Sha256::digest(vcard.as_bytes())[..16].iter().map(|b| format!("{b:02x}")).collect()
}

/// File name of the CardDAV resource for a new card.
pub fn href_for(uid: &str) -> String {
    let safe = !uid.is_empty()
        && uid.len() <= 200
        && uid.chars().all(|c| c.is_ascii_alphanumeric() || "-_.@".contains(c));
    if safe {
        format!("{uid}.vcf")
    } else {
        format!("{}.vcf", uuid::Uuid::new_v4())
    }
}

pub fn new_uid() -> String {
    uuid::Uuid::new_v4().to_string()
}

/// Columns derived from the vCard text.
pub struct Indexed {
    pub uid: String,
    #[allow(dead_code)]
    pub kind: String,
    pub full_name: String,
    pub search: String,
}

pub fn index(card: &Card, fallback_uid: &str) -> Indexed {
    let data = vcard::extract(card);
    Indexed {
        uid: card.uid().unwrap_or_else(|| fallback_uid.to_string()),
        kind: card.kind(),
        search: vcard::search_text(&data),
        full_name: data.full_name,
    }
}

/// Every user has at least one address book (the first one named in the user's language).
pub async fn ensure_default_book(db: &SqlitePool, user_id: &str) -> AppResult<()> {
    let (count,): (i64,) = sqlx::query_as("SELECT COUNT(*) FROM addressbooks WHERE user_id = ?")
        .bind(user_id)
        .fetch_one(db)
        .await?;
    if count == 0 {
        let lang = crate::i18n::user_lang(&mut *db.acquire().await?, user_id).await?;
        let name = crate::i18n::tr_in!(lang, "contacts.default_book_name");
        create_book(db, user_id, &uuid::Uuid::new_v4().to_string(), &name, "").await?;
    }
    Ok(())
}

pub async fn create_book(db: &SqlitePool, user_id: &str, id: &str, name: &str, description: &str) -> AppResult<()> {
    let now = time::to_db(time::now());
    sqlx::query("INSERT INTO addressbooks (id, user_id, name, description, revision, created_at, updated_at) VALUES (?, ?, ?, ?, 0, ?, ?)")
        .bind(id)
        .bind(user_id)
        .bind(name)
        .bind(description)
        .bind(&now)
        .bind(&now)
        .execute(db)
        .await?;
    Ok(())
}

pub async fn list_books(db: &SqlitePool, user_id: &str) -> AppResult<Vec<AddressBookRow>> {
    Ok(sqlx::query_as::<_, AddressBookRow>("SELECT * FROM addressbooks WHERE user_id = ? ORDER BY created_at, name")
        .bind(user_id)
        .fetch_all(db)
        .await?)
}

pub async fn owned_book(db: &SqlitePool, user_id: &str, id: &str) -> AppResult<AddressBookRow> {
    sqlx::query_as::<_, AddressBookRow>("SELECT * FROM addressbooks WHERE id = ? AND user_id = ?")
        .bind(id)
        .bind(user_id)
        .fetch_optional(db)
        .await?
        .ok_or(AppError::NotFound)
}

/// Increment the change counter so sync clients notice the change.
pub async fn bump(conn: &mut SqliteConnection, book_id: &str) -> AppResult<()> {
    sqlx::query("UPDATE addressbooks SET revision = revision + 1, updated_at = ? WHERE id = ?")
        .bind(time::to_db(time::now()))
        .bind(book_id)
        .execute(conn)
        .await?;
    Ok(())
}

pub async fn find_by_uid(conn: &mut SqliteConnection, book_id: &str, uid: &str) -> AppResult<Option<ContactRow>> {
    Ok(sqlx::query_as::<_, ContactRow>("SELECT * FROM contacts WHERE addressbook_id = ? AND uid = ?")
        .bind(book_id)
        .bind(uid)
        .fetch_optional(conn)
        .await?)
}

pub async fn insert(
    conn: &mut SqliteConnection,
    book_id: &str,
    href: &str,
    text: &str,
    idx: &Indexed,
) -> AppResult<String> {
    let id = uuid::Uuid::new_v4().to_string();
    let now = time::to_db(time::now());
    sqlx::query("INSERT INTO contacts (id, addressbook_id, uid, href, etag, vcard, kind, full_name, search, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
        .bind(&id)
        .bind(book_id)
        .bind(&idx.uid)
        .bind(href)
        .bind(etag_of(text))
        .bind(text)
        .bind(&idx.kind)
        .bind(&idx.full_name)
        .bind(&idx.search)
        .bind(&now)
        .bind(&now)
        .execute(&mut *conn)
        .await?;
    bump(conn, book_id).await?;
    crate::birthdays::sync_contact(conn, &id).await?;
    Ok(id)
}

pub async fn update(conn: &mut SqliteConnection, row: &ContactRow, text: &str, idx: &Indexed) -> AppResult<()> {
    sqlx::query("UPDATE contacts SET uid = ?, etag = ?, vcard = ?, kind = ?, full_name = ?, search = ?, updated_at = ? WHERE id = ?")
        .bind(&idx.uid)
        .bind(etag_of(text))
        .bind(text)
        .bind(&idx.kind)
        .bind(&idx.full_name)
        .bind(&idx.search)
        .bind(time::to_db(time::now()))
        .bind(&row.id)
        .execute(&mut *conn)
        .await?;
    bump(conn, &row.addressbook_id).await?;
    crate::birthdays::sync_contact(conn, &row.id).await
}

/// An associated birthday event is deleted along with it via `ON DELETE CASCADE`.
pub async fn delete(conn: &mut SqliteConnection, row: &ContactRow) -> AppResult<()> {
    sqlx::query("DELETE FROM contacts WHERE id = ?").bind(&row.id).execute(&mut *conn).await?;
    bump(conn, &row.addressbook_id).await
}
