//! Verwaltung für Administratoren: Übersicht, Benutzer, Einstellungen sowie Export und Import der ganzen Datenbank.

use axum::{
    body::Bytes,
    extract::{Path, Query, State},
    http::{header, StatusCode},
    response::IntoResponse,
    Json,
};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use crate::{
    auth::{hash_password, AuthUser},
    backup::{self, ImportError, ImportReport},
    error::{AppError, AppResult},
    settings::{Settings, TOKEN_TTL_RANGE},
    time, AppState,
};

use super::auth::{check_password, create_user, normalize_email};

async fn require_admin(state: &AppState, auth: &AuthUser) -> AppResult<()> {
    let is_admin: bool = sqlx::query_scalar("SELECT is_admin FROM users WHERE id = ?")
        .bind(&auth.id)
        .fetch_one(&state.db)
        .await?;
    if !is_admin {
        return Err(AppError::Forbidden("Nur für Administratoren".into()));
    }
    Ok(())
}

/// `GET /api/admin/export` – die ganze Datenbank als JSON-Datei
pub async fn export(State(state): State<AppState>, auth: AuthUser) -> AppResult<impl IntoResponse> {
    require_admin(&state, &auth).await?;
    let (export, _) = backup::export_db(&state.db).await?;
    let json = serde_json::to_vec_pretty(&export).map_err(anyhow::Error::from)?;
    let stamp = chrono::Local::now().format("%Y-%m-%d");
    Ok((
        [
            (header::CONTENT_TYPE, "application/json".to_string()),
            (header::CONTENT_DISPOSITION, format!("attachment; filename=\"synchaven-export-{stamp}.json\"")),
        ],
        json,
    ))
}

#[derive(Deserialize)]
pub struct ImportQuery {
    #[serde(default)]
    replace: bool,
}

/// `POST /api/admin/import?replace=true` – ersetzt alle Daten aller Benutzer durch den Export im Body
pub async fn import(
    State(state): State<AppState>,
    auth: AuthUser,
    Query(q): Query<ImportQuery>,
    body: Bytes,
) -> AppResult<Json<ImportReport>> {
    require_admin(&state, &auth).await?;
    let export = backup::parse(&body).map_err(map_err)?;
    // Eigener Task: Bricht der Browser die Anfrage ab, läuft der Import trotzdem sauber zu Ende
    // (sonst bliebe die Transaktion auf der Verbindung im Pool offen).
    let db = state.db.clone();
    let report = tokio::spawn(async move { backup::import_db(&db, &export, q.replace).await })
        .await
        .map_err(anyhow::Error::from)?
        .map_err(map_err)?;
    // Zwischengespeicherte CardDAV-Anmeldungen und Einstellungen gehören zu den alten Daten
    state.dav_auth.clear();
    state.settings.reload(&state.db).await?;
    tracing::info!("Datenbank aus Export importiert ({})", report.summary.join(", "));
    Ok(Json(report))
}

fn map_err(e: ImportError) -> AppError {
    match e {
        ImportError::Invalid(m) => AppError::BadRequest(m),
        ImportError::NotEmpty(m) => AppError::Conflict(m),
        ImportError::Other(e) => AppError::Internal(e),
    }
}

// ---------- Übersicht ----------

/// `GET /api/admin/overview` – Version, Laufzeit, Datenbankgröße, Anzahl der Einträge und Serverkonfiguration
pub async fn overview(State(state): State<AppState>, auth: AuthUser) -> AppResult<Json<Value>> {
    require_admin(&state, &auth).await?;
    let counts: (i64, i64, i64, i64, i64, i64, i64, i64) = sqlx::query_as(
        "SELECT \
           (SELECT COUNT(*) FROM users), \
           (SELECT COUNT(*) FROM users WHERE disabled = 1), \
           (SELECT COUNT(*) FROM calendars WHERE kind = 'default'), \
           (SELECT COUNT(*) FROM events WHERE contact_id IS NULL), \
           (SELECT COUNT(*) FROM addressbooks), \
           (SELECT COUNT(*) FROM contacts), \
           (SELECT COUNT(*) FROM tasks), \
           (SELECT COUNT(*) FROM push_subscriptions)",
    )
    .fetch_one(&state.db)
    .await?;
    let config = &state.config;
    // Datenbank samt Write-Ahead-Log
    let db_path = config.db_path();
    let db_size: u64 = ["", "-wal"]
        .iter()
        .filter_map(|suffix| std::fs::metadata(format!("{}{suffix}", db_path.display())).ok())
        .map(|m| m.len())
        .sum();
    Ok(Json(json!({
        "version": env!("SYNCHAVEN_VERSION"),
        "started_at": state.started_at,
        "database_size": db_size,
        "counts": {
            "users": counts.0,
            "disabled_users": counts.1,
            "calendars": counts.2,
            "events": counts.3,
            "addressbooks": counts.4,
            "contacts": counts.5,
            "tasks": counts.6,
            "push_subscriptions": counts.7,
        },
        "config": {
            "bind": config.bind.to_string(),
            "data_dir": config.data_dir.display().to_string(),
            "cors_origins": config.cors_origins,
            "vapid_subject": config.vapid_subject,
        },
    })))
}

// ---------- Einstellungen ----------

#[derive(Serialize)]
pub struct SettingsResponse {
    #[serde(flatten)]
    current: Settings,
    /// Werte aus den Umgebungsvariablen, die gelten, solange nichts gespeichert ist
    defaults: Settings,
}

fn settings_response(state: &AppState) -> Json<SettingsResponse> {
    Json(SettingsResponse { current: state.settings.get(), defaults: state.settings.defaults().clone() })
}

/// `GET /api/admin/settings`
pub async fn get_settings(State(state): State<AppState>, auth: AuthUser) -> AppResult<Json<SettingsResponse>> {
    require_admin(&state, &auth).await?;
    Ok(settings_response(&state))
}

#[derive(Deserialize)]
pub struct SettingsInput {
    allow_registration: Option<bool>,
    token_ttl_days: Option<i64>,
}

/// `PUT /api/admin/settings` – nur die übergebenen Felder werden geändert
pub async fn update_settings(
    State(state): State<AppState>,
    auth: AuthUser,
    Json(input): Json<SettingsInput>,
) -> AppResult<Json<SettingsResponse>> {
    require_admin(&state, &auth).await?;
    if input.token_ttl_days.is_some_and(|d| !TOKEN_TTL_RANGE.contains(&d)) {
        return Err(AppError::BadRequest(format!(
            "Die Gültigkeit der Anmeldung muss zwischen {} und {} Tagen liegen",
            TOKEN_TTL_RANGE.start(),
            TOKEN_TTL_RANGE.end()
        )));
    }
    state.settings.update(&state.db, input.allow_registration, input.token_ttl_days).await?;
    tracing::info!("Einstellungen geändert: {:?}", state.settings.get());
    Ok(settings_response(&state))
}

// ---------- Benutzer ----------

#[derive(sqlx::FromRow)]
struct AdminUserRow {
    id: String,
    email: String,
    name: String,
    is_admin: bool,
    disabled: bool,
    created_at: String,
    last_login_at: Option<String>,
    calendars: i64,
    events: i64,
    contacts: i64,
    tasks: i64,
    devices: i64,
}

#[derive(Serialize)]
pub struct AdminUser {
    id: String,
    email: String,
    name: String,
    is_admin: bool,
    disabled: bool,
    created_at: DateTime<Utc>,
    last_login_at: Option<DateTime<Utc>>,
    /// Eigene Kalender (ohne Geburtstagskalender)
    calendars: i64,
    /// Termine ohne Geburtstage
    events: i64,
    contacts: i64,
    tasks: i64,
    /// Browser mit eingeschalteten Benachrichtigungen
    devices: i64,
}

impl From<AdminUserRow> for AdminUser {
    fn from(r: AdminUserRow) -> Self {
        Self {
            id: r.id,
            email: r.email,
            name: r.name,
            is_admin: r.is_admin,
            disabled: r.disabled,
            created_at: time::from_db(&r.created_at),
            last_login_at: r.last_login_at.as_deref().map(time::from_db),
            calendars: r.calendars,
            events: r.events,
            contacts: r.contacts,
            tasks: r.tasks,
            devices: r.devices,
        }
    }
}

const USER_SELECT: &str = "SELECT u.id, u.email, u.name, u.is_admin, u.disabled, u.created_at, u.last_login_at, \
    (SELECT COUNT(*) FROM calendars c WHERE c.user_id = u.id AND c.kind = 'default') AS calendars, \
    (SELECT COUNT(*) FROM events e JOIN calendars c ON c.id = e.calendar_id \
       WHERE c.user_id = u.id AND e.contact_id IS NULL) AS events, \
    (SELECT COUNT(*) FROM contacts k JOIN addressbooks a ON a.id = k.addressbook_id WHERE a.user_id = u.id) AS contacts, \
    (SELECT COUNT(*) FROM tasks t JOIN task_lists l ON l.id = t.list_id WHERE l.user_id = u.id) AS tasks, \
    (SELECT COUNT(*) FROM push_subscriptions p WHERE p.user_id = u.id) AS devices \
    FROM users u";

async fn load_admin_user(state: &AppState, id: &str) -> AppResult<AdminUser> {
    Ok(sqlx::query_as::<_, AdminUserRow>(&format!("{USER_SELECT} WHERE u.id = ?"))
        .bind(id)
        .fetch_one(&state.db)
        .await?
        .into())
}

/// `GET /api/admin/users` – alle Konten, älteste zuerst
pub async fn list_users(State(state): State<AppState>, auth: AuthUser) -> AppResult<Json<Vec<AdminUser>>> {
    require_admin(&state, &auth).await?;
    let rows = sqlx::query_as::<_, AdminUserRow>(&format!("{USER_SELECT} ORDER BY u.created_at, u.rowid"))
        .fetch_all(&state.db)
        .await?;
    Ok(Json(rows.into_iter().map(Into::into).collect()))
}

#[derive(Deserialize)]
pub struct CreateUserInput {
    email: String,
    password: String,
    #[serde(default)]
    name: String,
    #[serde(default)]
    is_admin: bool,
}

/// `POST /api/admin/users` – Konto anlegen, auch wenn die Registrierung geschlossen ist
pub async fn create(
    State(state): State<AppState>,
    auth: AuthUser,
    Json(input): Json<CreateUserInput>,
) -> AppResult<(StatusCode, Json<AdminUser>)> {
    require_admin(&state, &auth).await?;
    let id = create_user(&state, &input.email, &input.name, &input.password, Some(input.is_admin)).await?;
    tracing::info!("Konto {} von der Verwaltung angelegt", input.email.trim().to_lowercase());
    Ok((StatusCode::CREATED, Json(load_admin_user(&state, &id).await?)))
}

#[derive(Deserialize)]
pub struct UpdateUserInput {
    name: Option<String>,
    email: Option<String>,
    /// Neues Passwort; meldet das Konto auf allen Geräten ab
    password: Option<String>,
    is_admin: Option<bool>,
    disabled: Option<bool>,
}

/// `PUT /api/admin/users/{id}` – nur die übergebenen Felder werden geändert
pub async fn update(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(id): Path<String>,
    Json(input): Json<UpdateUserInput>,
) -> AppResult<Json<AdminUser>> {
    require_admin(&state, &auth).await?;
    let user = super::auth::load_user(&state, &id).await?;
    let own = id == auth.id;
    // Wer sich selbst die Rechte entzieht oder sperrt, sperrt womöglich alle aus
    if own && input.is_admin == Some(false) {
        return Err(AppError::BadRequest("Du kannst dir die Administratorrechte nicht selbst entziehen".into()));
    }
    if own && input.disabled == Some(true) {
        return Err(AppError::BadRequest("Du kannst dein eigenes Konto nicht sperren".into()));
    }

    let name = match input.name.as_deref().map(str::trim) {
        Some("") => return Err(AppError::BadRequest("Der Name darf nicht leer sein".into())),
        Some(n) => n.chars().take(100).collect(),
        None => user.name.clone(),
    };
    let email = match input.email.as_deref() {
        Some(e) => {
            let email = normalize_email(e)?;
            let taken: Option<(String,)> = sqlx::query_as("SELECT id FROM users WHERE email = ? AND id <> ?")
                .bind(&email)
                .bind(&id)
                .fetch_optional(&state.db)
                .await?;
            if taken.is_some() {
                return Err(AppError::Conflict("Diese E-Mail-Adresse ist bereits registriert".into()));
            }
            email
        }
        None => user.email.clone(),
    };
    let new_hash = match input.password.as_deref().filter(|p| !p.is_empty()) {
        Some(pw) => {
            check_password(pw)?;
            let pw = pw.to_string();
            Some(tokio::task::spawn_blocking(move || hash_password(&pw)).await.map_err(anyhow::Error::from)??)
        }
        None => None,
    };
    // Neues Passwort: Andere Geräte müssen sich neu anmelden – das eigene bleibt angemeldet.
    let revoke = new_hash.is_some() && !own;

    sqlx::query(
        "UPDATE users SET name = ?, email = ?, password_hash = COALESCE(?, password_hash), is_admin = ?, disabled = ?, \
         tokens_valid_after = CASE WHEN ? THEN ? ELSE tokens_valid_after END, updated_at = ? WHERE id = ?",
    )
    .bind(&name)
    .bind(&email)
    .bind(&new_hash)
    .bind(input.is_admin.unwrap_or(user.is_admin))
    .bind(input.disabled.unwrap_or(user.disabled))
    .bind(revoke)
    .bind(Utc::now().timestamp())
    .bind(time::to_db(time::now()))
    .bind(&id)
    .execute(&state.db)
    .await?;
    tracing::info!("Konto {email} von der Verwaltung geändert");
    Ok(Json(load_admin_user(&state, &id).await?))
}

/// `POST /api/admin/users/{id}/logout` – alle Tokens des Kontos ungültig machen (CardDAV nutzt das Passwort)
pub async fn logout_everywhere(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(id): Path<String>,
) -> AppResult<Json<AdminUser>> {
    require_admin(&state, &auth).await?;
    if id == auth.id {
        return Err(AppError::BadRequest("Für das eigene Konto bitte normal abmelden".into()));
    }
    let res = sqlx::query("UPDATE users SET tokens_valid_after = ? WHERE id = ?")
        .bind(Utc::now().timestamp())
        .bind(&id)
        .execute(&state.db)
        .await?;
    if res.rows_affected() == 0 {
        return Err(AppError::NotFound);
    }
    Ok(Json(load_admin_user(&state, &id).await?))
}

/// `DELETE /api/admin/users/{id}` – Konto mit allen Daten löschen
pub async fn delete(State(state): State<AppState>, auth: AuthUser, Path(id): Path<String>) -> AppResult<StatusCode> {
    require_admin(&state, &auth).await?;
    if id == auth.id {
        return Err(AppError::BadRequest("Du kannst dein eigenes Konto nicht löschen".into()));
    }
    let email: Option<String> = sqlx::query_scalar("DELETE FROM users WHERE id = ? RETURNING email")
        .bind(&id)
        .fetch_optional(&state.db)
        .await?;
    let Some(email) = email else { return Err(AppError::NotFound) };
    tracing::info!("Konto {email} von der Verwaltung gelöscht");
    Ok(StatusCode::NO_CONTENT)
}
