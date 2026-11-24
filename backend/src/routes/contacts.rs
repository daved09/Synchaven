//! REST endpoints for address books and contacts incl. vCard import/export.

use std::collections::HashMap;

use axum::{
    extract::{Path, Query, State},
    http::{header, StatusCode},
    response::IntoResponse,
    Json,
};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use crate::{
    auth::AuthUser,
    contacts::{self, AddressBookRow, ContactRow},
    error::{AppError, AppResult},
    time::{self, from_db},
    vcard::{self, ContactData},
    AppState,
};

// ---------- Data types ----------

#[derive(Serialize)]
pub struct AddressBook {
    id: String,
    name: String,
    description: String,
    contact_count: i64,
    created_at: DateTime<Utc>,
    updated_at: DateTime<Utc>,
}

impl AddressBook {
    fn from_row(r: AddressBookRow, contact_count: i64) -> Self {
        Self {
            id: r.id,
            name: r.name,
            description: r.description,
            contact_count,
            created_at: from_db(&r.created_at),
            updated_at: from_db(&r.updated_at),
        }
    }
}

#[derive(Deserialize)]
pub struct AddressBookInput {
    name: String,
    #[serde(default)]
    description: String,
}

#[derive(Serialize)]
pub struct Contact {
    id: String,
    addressbook_id: String,
    uid: String,
    /// Changes with every change of the vCard (also of the photo) – for syncing
    etag: String,
    #[serde(flatten)]
    data: ContactData,
    has_photo: bool,
    /// Only for single queries: photo as a data: URL
    #[serde(skip_serializing_if = "Option::is_none")]
    photo: Option<String>,
    created_at: DateTime<Utc>,
    updated_at: DateTime<Utc>,
}

impl Contact {
    fn from_row(r: &ContactRow, with_photo: bool) -> Self {
        let card = vcard::parse_cards(&r.vcard).into_iter().next().unwrap_or_default();
        let photo = vcard::photo(&card);
        Self {
            id: r.id.clone(),
            addressbook_id: r.addressbook_id.clone(),
            uid: r.uid.clone(),
            etag: r.etag.clone(),
            data: vcard::extract(&card),
            has_photo: photo.is_some(),
            photo: if with_photo { photo } else { None },
            created_at: from_db(&r.created_at),
            updated_at: from_db(&r.updated_at),
        }
    }
}

#[derive(Deserialize)]
pub struct ContactInput {
    addressbook_id: String,
    /// New photo as a data: URL, `""` removes it; if the field is missing, the photo stays unchanged.
    #[serde(default)]
    photo: Option<String>,
    /// If the field is missing (older clients), the categories stay unchanged.
    #[serde(default)]
    categories: Option<Vec<String>>,
    #[serde(flatten)]
    data: ContactData,
}

impl ContactInput {
    /// Form data; without a `categories` field, the existing categories.
    fn data(self, old: &[String]) -> ContactData {
        ContactData { categories: self.categories.unwrap_or_else(|| old.to_vec()), ..self.data }
    }
}

/// Set REV to the current time.
fn touch(card: &mut vcard::Card) {
    card.lines.retain(|l| l.name != "REV");
    card.lines.push(vcard::Line::new("REV", vcard::rev_value(time::now())));
}

fn apply_photo(card: &mut vcard::Card, photo: Option<&str>) -> AppResult<()> {
    match photo {
        Some(p) if p.len() > 5_000_000 => Err(AppError::BadRequest("Das Foto ist zu groß (max. 3,5 MB)".into())),
        Some(p) if !vcard::set_photo(card, p) => {
            Err(AppError::BadRequest("Das Foto muss als data:-URL (Base64) übergeben werden".into()))
        }
        _ => Ok(()),
    }
}

// ---------- Address books ----------

fn validate_book(input: &AddressBookInput) -> AppResult<String> {
    let name = input.name.trim();
    if name.is_empty() || name.chars().count() > 100 {
        return Err(AppError::BadRequest("Der Name muss 1–100 Zeichen lang sein".into()));
    }
    Ok(name.to_string())
}

async fn counts(state: &AppState, user_id: &str) -> AppResult<HashMap<String, i64>> {
    let rows: Vec<(String, i64)> = sqlx::query_as(
        "SELECT c.addressbook_id, COUNT(*) FROM contacts c JOIN addressbooks b ON b.id = c.addressbook_id
         WHERE b.user_id = ? AND c.kind <> 'group' GROUP BY c.addressbook_id",
    )
    .bind(user_id)
    .fetch_all(&state.db)
    .await?;
    Ok(rows.into_iter().collect())
}

async fn book_response(state: &AppState, user_id: &str, id: &str) -> AppResult<AddressBook> {
    let row = contacts::owned_book(&state.db, user_id, id).await?;
    let n = counts(state, user_id).await?.get(id).copied().unwrap_or(0);
    Ok(AddressBook::from_row(row, n))
}

pub async fn list_books(State(state): State<AppState>, auth: AuthUser) -> AppResult<Json<Vec<AddressBook>>> {
    contacts::ensure_default_book(&state.db, &auth.id).await?;
    let counts = counts(&state, &auth.id).await?;
    let rows = contacts::list_books(&state.db, &auth.id).await?;
    Ok(Json(
        rows.into_iter()
            .map(|r| {
                let n = counts.get(&r.id).copied().unwrap_or(0);
                AddressBook::from_row(r, n)
            })
            .collect(),
    ))
}

pub async fn create_book(
    State(state): State<AppState>,
    auth: AuthUser,
    Json(input): Json<AddressBookInput>,
) -> AppResult<(StatusCode, Json<AddressBook>)> {
    let name = validate_book(&input)?;
    let id = uuid::Uuid::new_v4().to_string();
    contacts::create_book(&state.db, &auth.id, &id, &name, input.description.trim()).await?;
    Ok((StatusCode::CREATED, Json(book_response(&state, &auth.id, &id).await?)))
}

pub async fn update_book(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(id): Path<String>,
    Json(input): Json<AddressBookInput>,
) -> AppResult<Json<AddressBook>> {
    contacts::owned_book(&state.db, &auth.id, &id).await?;
    let name = validate_book(&input)?;
    sqlx::query("UPDATE addressbooks SET name = ?, description = ?, revision = revision + 1, updated_at = ? WHERE id = ?")
        .bind(&name)
        .bind(input.description.trim())
        .bind(time::to_db(time::now()))
        .bind(&id)
        .execute(&state.db)
        .await?;
    Ok(Json(book_response(&state, &auth.id, &id).await?))
}

pub async fn delete_book(State(state): State<AppState>, auth: AuthUser, Path(id): Path<String>) -> AppResult<StatusCode> {
    contacts::owned_book(&state.db, &auth.id, &id).await?;
    // Contacts are deleted along with it via ON DELETE CASCADE.
    sqlx::query("DELETE FROM addressbooks WHERE id = ?").bind(&id).execute(&state.db).await?;
    Ok(StatusCode::NO_CONTENT)
}

pub async fn export_book(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(id): Path<String>,
) -> AppResult<impl IntoResponse> {
    let book = contacts::owned_book(&state.db, &auth.id, &id).await?;
    let rows: Vec<(String,)> = sqlx::query_as("SELECT vcard FROM contacts WHERE addressbook_id = ? ORDER BY full_name COLLATE NOCASE")
        .bind(&id)
        .fetch_all(&state.db)
        .await?;
    let mut body = String::new();
    for (text,) in rows {
        body.push_str(text.trim_end());
        body.push_str("\r\n");
    }
    let filename: String = book
        .name
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() || c == '-' || c == '_' { c } else { '_' })
        .collect();
    Ok((
        [
            (header::CONTENT_TYPE, "text/vcard; charset=utf-8".to_string()),
            (header::CONTENT_DISPOSITION, format!("attachment; filename=\"{filename}.vcf\"")),
        ],
        body,
    ))
}

/// Imports a .vcf file (any number of cards, vCard 2.1/3.0/4.0).
/// Contacts with an existing UID are updated.
pub async fn import_book(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(id): Path<String>,
    body: String,
) -> AppResult<Json<Value>> {
    contacts::owned_book(&state.db, &auth.id, &id).await?;
    let cards = vcard::parse_cards(&body);
    if cards.is_empty() {
        return Err(AppError::BadRequest("Keine vCards gefunden (BEGIN:VCARD fehlt)".into()));
    }
    let (mut created, mut updated, mut unchanged) = (0usize, 0usize, 0usize);
    let mut skipped = Vec::new();
    let mut tx = state.db.begin().await?;
    for card in cards {
        // Cards without a UID (e.g. vCard 2.1 exports from phones) get a UID derived from
        // their content, so importing the same file again doesn't create duplicates.
        let h = contacts::etag_of(&card.serialize());
        let stable_uid = || format!("{}-{}-{}-{}-{}", &h[..8], &h[8..12], &h[12..16], &h[16..20], &h[20..32]);
        let card = vcard::normalize_import(card, stable_uid);
        let text = card.serialize();
        let idx = contacts::index(&card, "");
        if idx.uid.len() > 500 {
            skipped.push(format!("{}: UID zu lang", idx.full_name));
            continue;
        }
        match contacts::find_by_uid(&mut tx, &id, &idx.uid).await? {
            Some(existing) => {
                if existing.vcard == text {
                    unchanged += 1;
                } else {
                    contacts::update(&mut tx, &existing, &text, &idx).await?;
                    updated += 1;
                }
            }
            None => {
                contacts::insert(&mut tx, &id, &contacts::href_for(&idx.uid), &text, &idx).await?;
                created += 1;
            }
        }
    }
    tx.commit().await?;
    Ok(Json(json!({
        "imported": created + updated + unchanged,
        "created": created,
        "updated": updated,
        "unchanged": unchanged,
        "skipped": skipped,
    })))
}

// ---------- Contacts ----------

async fn owned_contact(state: &AppState, user_id: &str, id: &str) -> AppResult<ContactRow> {
    sqlx::query_as::<_, ContactRow>(
        "SELECT c.* FROM contacts c JOIN addressbooks b ON b.id = c.addressbook_id WHERE c.id = ? AND b.user_id = ?",
    )
    .bind(id)
    .bind(user_id)
    .fetch_optional(&state.db)
    .await?
    .ok_or(AppError::NotFound)
}

fn validate_contact(mut data: ContactData) -> AppResult<ContactData> {
    data.normalize();
    if data.full_name.is_empty() {
        return Err(AppError::BadRequest(
            "Bitte einen Namen, eine Firma, E-Mail-Adresse oder Telefonnummer angeben".into(),
        ));
    }
    if data.full_name.chars().count() > 500 || data.note.chars().count() > 20_000 {
        return Err(AppError::BadRequest("Name oder Notiz ist zu lang".into()));
    }
    if data.categories.len() > 100 || data.categories.iter().any(|c| c.chars().count() > 100) {
        return Err(AppError::BadRequest("Zu viele oder zu lange Kategorien".into()));
    }
    let b = &data.birthday;
    let valid_bday = b.is_empty()
        || chrono::NaiveDate::parse_from_str(b, "%Y-%m-%d").is_ok()
        || b.strip_prefix("--").is_some_and(|md| {
            chrono::NaiveDate::parse_from_str(&format!("2000-{md}"), "%Y-%m-%d").is_ok()
        });
    if !valid_bday {
        return Err(AppError::BadRequest("Ungültiger Geburtstag".into()));
    }
    Ok(data)
}

#[derive(Deserialize)]
pub struct ListQuery {
    addressbook_id: Option<String>,
    q: Option<String>,
}

pub async fn list(
    State(state): State<AppState>,
    auth: AuthUser,
    Query(q): Query<ListQuery>,
) -> AppResult<Json<Vec<Contact>>> {
    let search = q.q.as_deref().map(str::trim).filter(|s| !s.is_empty()).map(|s| {
        let escaped = s.to_lowercase().replace('\\', "\\\\").replace('%', "\\%").replace('_', "\\_");
        format!("%{escaped}%")
    });
    let rows = sqlx::query_as::<_, ContactRow>(
        "SELECT c.* FROM contacts c JOIN addressbooks b ON b.id = c.addressbook_id
         WHERE b.user_id = ? AND c.kind <> 'group'
           AND (? IS NULL OR c.addressbook_id = ?)
           AND (? IS NULL OR c.search LIKE ? ESCAPE '\\')
         ORDER BY c.full_name COLLATE NOCASE",
    )
    .bind(&auth.id)
    .bind(&q.addressbook_id)
    .bind(&q.addressbook_id)
    .bind(&search)
    .bind(&search)
    .fetch_all(&state.db)
    .await?;
    Ok(Json(rows.iter().map(|r| Contact::from_row(r, false)).collect()))
}

pub async fn get(State(state): State<AppState>, auth: AuthUser, Path(id): Path<String>) -> AppResult<Json<Contact>> {
    Ok(Json(Contact::from_row(&owned_contact(&state, &auth.id, &id).await?, true)))
}

pub async fn create(
    State(state): State<AppState>,
    auth: AuthUser,
    Json(input): Json<ContactInput>,
) -> AppResult<(StatusCode, Json<Contact>)> {
    contacts::owned_book(&state.db, &auth.id, &input.addressbook_id).await?;
    let book_id = input.addressbook_id.clone();
    let photo = input.photo.clone();
    let data = validate_contact(input.data(&[]))?;
    let uid = contacts::new_uid();
    let mut card = vcard::create(&uid, &data, time::now());
    apply_photo(&mut card, photo.as_deref())?;
    let text = card.serialize();
    let mut conn = state.db.acquire().await?;
    let id = contacts::insert(&mut conn, &book_id, &contacts::href_for(&uid), &text, &contacts::index(&card, &uid))
        .await?;
    drop(conn);
    Ok((StatusCode::CREATED, Json(Contact::from_row(&owned_contact(&state, &auth.id, &id).await?, true))))
}

pub async fn update(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(id): Path<String>,
    Json(input): Json<ContactInput>,
) -> AppResult<Json<Contact>> {
    let row = owned_contact(&state, &auth.id, &id).await?;
    let mut card = vcard::parse_cards(&row.vcard).into_iter().next().unwrap_or_default();
    let old = vcard::extract(&card);
    apply_photo(&mut card, input.photo.as_deref())?;
    let input_book = input.addressbook_id.clone();
    let new = validate_contact(input.data(&old.categories))?;
    vcard::apply(&mut card, &old, &new);
    touch(&mut card);
    let text = card.serialize();
    let idx = contacts::index(&card, &row.uid);

    let mut tx = state.db.begin().await?;
    if input_book != row.addressbook_id {
        // Move to another address book
        contacts::owned_book(&state.db, &auth.id, &input_book).await?;
        if contacts::find_by_uid(&mut tx, &input_book, &idx.uid).await?.is_some() {
            return Err(AppError::Conflict("Der Kontakt existiert im Ziel-Adressbuch bereits".into()));
        }
        let (taken,): (i64,) = sqlx::query_as("SELECT COUNT(*) FROM contacts WHERE addressbook_id = ? AND href = ?")
            .bind(&input_book)
            .bind(&row.href)
            .fetch_one(&mut *tx)
            .await?;
        let href = if taken > 0 { contacts::href_for(&contacts::new_uid()) } else { row.href.clone() };
        sqlx::query("UPDATE contacts SET addressbook_id = ?, href = ? WHERE id = ?")
            .bind(&input_book)
            .bind(&href)
            .bind(&row.id)
            .execute(&mut *tx)
            .await?;
        contacts::bump(&mut tx, &row.addressbook_id).await?;
        let moved = ContactRow { addressbook_id: input_book.clone(), href, ..row };
        contacts::update(&mut tx, &moved, &text, &idx).await?;
    } else {
        contacts::update(&mut tx, &row, &text, &idx).await?;
    }
    tx.commit().await?;
    Ok(Json(Contact::from_row(&owned_contact(&state, &auth.id, &id).await?, true)))
}

pub async fn delete(State(state): State<AppState>, auth: AuthUser, Path(id): Path<String>) -> AppResult<StatusCode> {
    let row = owned_contact(&state, &auth.id, &id).await?;
    let mut conn = state.db.acquire().await?;
    contacts::delete(&mut conn, &row).await?;
    Ok(StatusCode::NO_CONTENT)
}

// ---------- Categories ----------

#[derive(Deserialize)]
pub struct CategoryInput {
    name: String,
    /// Only when renaming
    #[serde(default)]
    new_name: String,
}

/// Renames a category in all of the user's contacts (`new_name = None`: removes it).
async fn rewrite_category(state: &AppState, user_id: &str, name: &str, new_name: Option<&str>) -> AppResult<Json<Value>> {
    let name = name.trim().to_lowercase();
    if name.is_empty() {
        return Err(AppError::BadRequest("Kategorie fehlt".into()));
    }
    if new_name.is_some_and(|n| n.trim().is_empty() || n.trim().chars().count() > 100) {
        return Err(AppError::BadRequest("Der Name muss 1–100 Zeichen lang sein".into()));
    }
    // The categories are part of the search text – a pre-selection, the actual check is done on the card.
    let pattern = format!("%{}%", name.replace('\\', "\\\\").replace('%', "\\%").replace('_', "\\_"));
    let rows = sqlx::query_as::<_, ContactRow>(
        "SELECT c.* FROM contacts c JOIN addressbooks b ON b.id = c.addressbook_id
         WHERE b.user_id = ? AND c.search LIKE ? ESCAPE '\\'",
    )
    .bind(user_id)
    .bind(&pattern)
    .fetch_all(&state.db)
    .await?;
    let mut tx = state.db.begin().await?;
    let mut updated = 0;
    for row in rows {
        let Some(mut card) = vcard::parse_cards(&row.vcard).into_iter().next() else { continue };
        let old = vcard::extract(&card);
        if !old.categories.iter().any(|c| c.to_lowercase() == name) {
            continue;
        }
        let renamed: Vec<&str> = old
            .categories
            .iter()
            .filter_map(|c| if c.to_lowercase() == name { new_name } else { Some(c.as_str()) })
            .collect();
        let new = ContactData { categories: vcard::clean_categories(&renamed), ..old.clone() };
        vcard::apply(&mut card, &old, &new);
        touch(&mut card);
        contacts::update(&mut tx, &row, &card.serialize(), &contacts::index(&card, &row.uid)).await?;
        updated += 1;
    }
    tx.commit().await?;
    Ok(Json(json!({ "updated": updated })))
}

pub async fn rename_category(
    State(state): State<AppState>,
    auth: AuthUser,
    Json(input): Json<CategoryInput>,
) -> AppResult<Json<Value>> {
    rewrite_category(&state, &auth.id, &input.name, Some(&input.new_name)).await
}

pub async fn delete_category(
    State(state): State<AppState>,
    auth: AuthUser,
    Json(input): Json<CategoryInput>,
) -> AppResult<Json<Value>> {
    rewrite_category(&state, &auth.id, &input.name, None).await
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn missing_categories_keep_old_ones() {
        let old = vec!["Familie".to_string()];
        let input: ContactInput = serde_json::from_str(r#"{"addressbook_id":"b","given_name":"A"}"#).unwrap();
        assert_eq!(input.data(&old).categories, old);
        let input: ContactInput =
            serde_json::from_str(r#"{"addressbook_id":"b","given_name":"A","categories":["Arbeit"]}"#).unwrap();
        let data = input.data(&old);
        assert_eq!((data.categories, data.given_name.as_str()), (vec!["Arbeit".to_string()], "A"));
        let input: ContactInput = serde_json::from_str(r#"{"addressbook_id":"b","categories":[]}"#).unwrap();
        assert!(input.data(&old).categories.is_empty());
    }
}
