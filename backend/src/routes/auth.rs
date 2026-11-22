use axum::{extract::State, http::StatusCode, Json};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

use crate::{
    auth::{hash_password, verify_password, AuthUser},
    error::{AppError, AppResult},
    models::{User, UserRow},
    time, AppState,
};

#[derive(Deserialize)]
pub struct RegisterInput {
    email: String,
    password: String,
    #[serde(default)]
    name: String,
}

#[derive(Deserialize)]
pub struct LoginInput {
    email: String,
    password: String,
}

#[derive(Deserialize)]
pub struct UpdateMeInput {
    name: Option<String>,
    email: Option<String>,
    current_password: Option<String>,
    new_password: Option<String>,
    default_view: Option<String>,
}

#[derive(Serialize)]
pub struct AuthResponse {
    token: String,
    token_type: &'static str,
    expires_at: DateTime<Utc>,
    user: User,
}

fn normalize_email(email: &str) -> AppResult<String> {
    let email = email.trim().to_lowercase();
    let valid = email.len() <= 254
        && email.split_once('@').is_some_and(|(l, d)| !l.is_empty() && d.contains('.') && !d.starts_with('.'));
    if !valid {
        return Err(AppError::BadRequest("Ungültige E-Mail-Adresse".into()));
    }
    Ok(email)
}

const VIEW_MODES: [&str; 4] = ["month", "week", "day", "list"];

fn check_password(pw: &str) -> AppResult<()> {
    if pw.chars().count() < 8 {
        return Err(AppError::BadRequest("Das Passwort muss mindestens 8 Zeichen lang sein".into()));
    }
    if pw.len() > 1024 {
        return Err(AppError::BadRequest("Das Passwort ist zu lang".into()));
    }
    Ok(())
}

async fn load_user(state: &AppState, id: &str) -> AppResult<UserRow> {
    Ok(sqlx::query_as::<_, UserRow>("SELECT * FROM users WHERE id = ?")
        .bind(id)
        .fetch_one(&state.db)
        .await?)
}

fn respond(state: &AppState, user: UserRow) -> AppResult<Json<AuthResponse>> {
    let (token, expires_at) = state.jwt.issue(&user.id)?;
    Ok(Json(AuthResponse { token, token_type: "Bearer", expires_at, user: user.into() }))
}

pub async fn register(
    State(state): State<AppState>,
    Json(input): Json<RegisterInput>,
) -> AppResult<(StatusCode, Json<AuthResponse>)> {
    if !state.config.allow_registration {
        return Err(AppError::Forbidden("Registrierung ist deaktiviert".into()));
    }
    let email = normalize_email(&input.email)?;
    check_password(&input.password)?;
    let name = match input.name.trim() {
        "" => email.split('@').next().unwrap_or("").to_string(),
        n => n.chars().take(100).collect(),
    };

    let exists: Option<(String,)> = sqlx::query_as("SELECT id FROM users WHERE email = ?")
        .bind(&email)
        .fetch_optional(&state.db)
        .await?;
    if exists.is_some() {
        return Err(AppError::Conflict("Diese E-Mail-Adresse ist bereits registriert".into()));
    }

    let hash = hash_password(&input.password)?;
    let id = uuid::Uuid::new_v4().to_string();
    let now = time::to_db(time::now());
    let mut tx = state.db.begin().await?;
    sqlx::query("INSERT INTO users (id, email, name, password_hash, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)")
        .bind(&id)
        .bind(&email)
        .bind(&name)
        .bind(&hash)
        .bind(&now)
        .bind(&now)
        .execute(&mut *tx)
        .await?;
    // Jeder neue Benutzer erhält einen Standardkalender.
    sqlx::query("INSERT INTO calendars (id, user_id, name, color, description, visible, created_at, updated_at) VALUES (?, ?, 'Persönlich', '#6750A4', '', 1, ?, ?)")
        .bind(uuid::Uuid::new_v4().to_string())
        .bind(&id)
        .bind(&now)
        .bind(&now)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;

    let user = load_user(&state, &id).await?;
    Ok((StatusCode::CREATED, respond(&state, user)?))
}

pub async fn login(State(state): State<AppState>, Json(input): Json<LoginInput>) -> AppResult<Json<AuthResponse>> {
    let email = input.email.trim().to_lowercase();
    let user = sqlx::query_as::<_, UserRow>("SELECT * FROM users WHERE email = ?")
        .bind(&email)
        .fetch_optional(&state.db)
        .await?;
    let password = input.password;
    // Hash-Prüfung im Blocking-Pool, sie ist absichtlich rechenintensiv.
    let ok = match &user {
        Some(u) => {
            let hash = u.password_hash.clone();
            tokio::task::spawn_blocking(move || verify_password(&password, &hash))
                .await
                .unwrap_or(false)
        }
        None => false,
    };
    match user {
        Some(u) if ok => respond(&state, u),
        _ => Err(AppError::Unauthorized("E-Mail-Adresse oder Passwort falsch".into())),
    }
}

pub async fn me(State(state): State<AppState>, auth: AuthUser) -> AppResult<Json<User>> {
    Ok(Json(load_user(&state, &auth.id).await?.into()))
}

pub async fn update_me(
    State(state): State<AppState>,
    auth: AuthUser,
    Json(input): Json<UpdateMeInput>,
) -> AppResult<Json<User>> {
    let user = load_user(&state, &auth.id).await?;
    let mut name = user.name.clone();
    let mut email = user.email.clone();
    let mut hash = user.password_hash.clone();
    let mut default_view = user.default_view.clone();

    if let Some(n) = input.name.as_deref().map(str::trim).filter(|n| !n.is_empty()) {
        name = n.chars().take(100).collect();
    }

    let wants_email = input.email.as_deref().is_some_and(|e| e.trim().to_lowercase() != user.email);
    let wants_password = input.new_password.as_deref().is_some_and(|p| !p.is_empty());
    if wants_email || wants_password {
        let current = input.current_password.as_deref().unwrap_or("");
        if !verify_password(current, &user.password_hash) {
            return Err(AppError::Unauthorized("Aktuelles Passwort ist falsch".into()));
        }
    }
    if wants_email {
        email = normalize_email(input.email.as_deref().unwrap())?;
        let taken: Option<(String,)> = sqlx::query_as("SELECT id FROM users WHERE email = ? AND id <> ?")
            .bind(&email)
            .bind(&user.id)
            .fetch_optional(&state.db)
            .await?;
        if taken.is_some() {
            return Err(AppError::Conflict("Diese E-Mail-Adresse ist bereits registriert".into()));
        }
    }
    if let Some(v) = input.default_view.as_deref() {
        if !VIEW_MODES.contains(&v) {
            return Err(AppError::BadRequest("Ungültige Standardansicht".into()));
        }
        default_view = v.to_string();
    }
    if wants_password {
        let pw = input.new_password.as_deref().unwrap();
        check_password(pw)?;
        hash = hash_password(pw)?;
    }

    sqlx::query("UPDATE users SET name = ?, email = ?, password_hash = ?, default_view = ?, updated_at = ? WHERE id = ?")
        .bind(&name)
        .bind(&email)
        .bind(&hash)
        .bind(&default_view)
        .bind(time::to_db(time::now()))
        .bind(&user.id)
        .execute(&state.db)
        .await?;
    Ok(Json(load_user(&state, &auth.id).await?.into()))
}
