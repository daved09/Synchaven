//! REST-Endpunkte für Adressbücher und Kontakte inkl. vCard-Import/-Export.

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

// ---------- Datentypen ----------

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
    /// Ändert sich mit jeder Änderung der vCard (auch des Fotos) – für die Synchronisierung
    etag: String,
    #[serde(flatten)]
    data: ContactData,
    has_photo: bool,
    /// Nur bei Einzelabfrage: Foto als data:-URL
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
    /// Neues Foto als data:-URL, `""` entfernt es, fehlt das Feld, bleibt das Foto unverändert.
    #[serde(default)]
    photo: Option<String>,
    #[serde(flatten)]
    data: ContactData,
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

// ---------- Adressbücher ----------

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
    // Kontakte werden per ON DELETE CASCADE mitgelöscht.
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

/// Importiert eine .vcf-Datei (beliebig viele Karten, vCard 2.1/3.0/4.0).
/// Kontakte mit bereits vorhandener UID werden aktualisiert.
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
        // Karten ohne UID (z. B. vCard-2.1-Exporte von Handys) erhalten eine aus dem Inhalt
        // abgeleitete UID, damit ein erneuter Import derselben Datei keine Duplikate erzeugt.
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

// ---------- Kontakte ----------

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
    let data = validate_contact(input.data)?;
    let uid = contacts::new_uid();
    let mut card = vcard::create(&uid, &data, time::now());
    apply_photo(&mut card, input.photo.as_deref())?;
    let text = card.serialize();
    let mut conn = state.db.acquire().await?;
    let id = contacts::insert(&mut conn, &input.addressbook_id, &contacts::href_for(&uid), &text, &contacts::index(&card, &uid))
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
    let new = validate_contact(input.data)?;
    let mut card = vcard::parse_cards(&row.vcard).into_iter().next().unwrap_or_default();
    let old = vcard::extract(&card);
    vcard::apply(&mut card, &old, &new);
    apply_photo(&mut card, input.photo.as_deref())?;
    card.lines.retain(|l| l.name != "REV");
    card.lines.push(vcard::Line::new("REV", vcard::rev_value(time::now())));
    let text = card.serialize();
    let idx = contacts::index(&card, &row.uid);

    let mut tx = state.db.begin().await?;
    if input.addressbook_id != row.addressbook_id {
        // In ein anderes Adressbuch verschieben
        contacts::owned_book(&state.db, &auth.id, &input.addressbook_id).await?;
        if contacts::find_by_uid(&mut tx, &input.addressbook_id, &idx.uid).await?.is_some() {
            return Err(AppError::Conflict("Der Kontakt existiert im Ziel-Adressbuch bereits".into()));
        }
        let (taken,): (i64,) = sqlx::query_as("SELECT COUNT(*) FROM contacts WHERE addressbook_id = ? AND href = ?")
            .bind(&input.addressbook_id)
            .bind(&row.href)
            .fetch_one(&mut *tx)
            .await?;
        let href = if taken > 0 { contacts::href_for(&contacts::new_uid()) } else { row.href.clone() };
        sqlx::query("UPDATE contacts SET addressbook_id = ?, href = ? WHERE id = ?")
            .bind(&input.addressbook_id)
            .bind(&href)
            .bind(&row.id)
            .execute(&mut *tx)
            .await?;
        contacts::bump(&mut tx, &row.addressbook_id).await?;
        let moved = ContactRow { addressbook_id: input.addressbook_id.clone(), href, ..row };
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
