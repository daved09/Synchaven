use crate::i18n::{tr, tr_in, Lang};
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
    /// Language for the initial data (`de`, `en`); default: the request's language
    language: Option<String>,
    /// One-time token from the server log – only for the first account (initial setup)
    setup_token: Option<String>,
}

#[derive(Deserialize)]
pub struct LoginInput {
    email: String,
    password: String,
    /// Code of the authenticator app or a recovery code – only for accounts with two-factor authentication.
    /// Without it, such an account gets the error `totp_required` after a correct password.
    code: Option<String>,
}

#[derive(Deserialize)]
pub struct UpdateMeInput {
    name: Option<String>,
    email: Option<String>,
    current_password: Option<String>,
    new_password: Option<String>,
    default_view: Option<String>,
    language: Option<String>,
}

#[derive(Serialize)]
pub struct AuthResponse {
    token: String,
    token_type: &'static str,
    expires_at: DateTime<Utc>,
    user: User,
}

pub(super) fn normalize_email(email: &str) -> AppResult<String> {
    let email = email.trim().to_lowercase();
    let valid = email.len() <= 254
        && email.split_once('@').is_some_and(|(l, d)| !l.is_empty() && d.contains('.') && !d.starts_with('.'));
    if !valid {
        return Err(AppError::BadRequest(tr!("auth.invalid_email")));
    }
    Ok(email)
}

const VIEW_MODES: [&str; 4] = ["month", "week", "day", "list"];

pub(super) fn check_password(pw: &str) -> AppResult<()> {
    if pw.chars().count() < 8 {
        return Err(AppError::BadRequest(tr!("auth.password_too_short")));
    }
    if pw.len() > 1024 {
        return Err(AppError::BadRequest(tr!("auth.password_too_long")));
    }
    Ok(())
}

pub(super) async fn load_user(state: &AppState, id: &str) -> AppResult<UserRow> {
    Ok(sqlx::query_as::<_, UserRow>("SELECT * FROM users WHERE id = ?")
        .bind(id)
        .fetch_one(&state.db)
        .await?)
}

fn respond(state: &AppState, user: UserRow) -> AppResult<Json<AuthResponse>> {
    let (token, expires_at) = state.jwt.issue(&user.id, state.settings.get().token_ttl_days)?;
    Ok(Json(AuthResponse { token, token_type: "Bearer", expires_at, user: user.into() }))
}

/// May new accounts register themselves (admin setting, otherwise `ALLOW_REGISTRATION`)? Not during the initial
/// setup: the first account – the administrator – needs the setup token (see [crate::setup]).
pub async fn registration_open(state: &AppState) -> AppResult<bool> {
    Ok(state.settings.get().allow_registration && !crate::setup::pending(&state.db).await?)
}

/// New token for the signed-in session – so clients such as the Android sync stay signed in
/// without storing the password.
pub async fn refresh(State(state): State<AppState>, auth: AuthUser) -> AppResult<Json<AuthResponse>> {
    touch_login(&state, &auth.id).await?;
    respond(&state, load_user(&state, &auth.id).await?)
}

/// Records the last sign-in for the user list in the admin page.
async fn touch_login(state: &AppState, id: &str) -> AppResult<()> {
    sqlx::query("UPDATE users SET last_login_at = ? WHERE id = ?")
        .bind(time::to_db(time::now()))
        .bind(id)
        .execute(&state.db)
        .await?;
    Ok(())
}

pub async fn register(
    State(state): State<AppState>,
    Json(input): Json<RegisterInput>,
) -> AppResult<(StatusCode, Json<AuthResponse>)> {
    let initial_setup = crate::setup::pending(&state.db).await?;
    if initial_setup {
        if !state.setup.check(input.setup_token.as_deref()).await? {
            tracing::warn!("Rejected registration of the first account: missing or wrong setup token");
            return Err(AppError::Forbidden(tr!("auth.setup_token_invalid")));
        }
    } else if !registration_open(&state).await? {
        return Err(AppError::Forbidden(tr!("auth.registration_disabled")));
    }
    let lang = crate::i18n::parse_choice(input.language.as_deref())?.unwrap_or_else(crate::i18n::current);
    let id = create_user(&state, &input.email, &input.name, &input.password, None, lang).await?;
    if initial_setup {
        state.setup.finish().await;
    }
    touch_login(&state, &id).await?;
    let user = load_user(&state, &id).await?;
    if user.is_admin {
        tracing::info!("Account {} registered (administrator)", user.id);
    } else {
        tracing::info!("Account {} registered", user.id);
    }
    Ok((StatusCode::CREATED, respond(&state, user)?))
}

/// Creates an account with its initial data – a calendar, a task list, an address book and the birthday
/// calendar, named in `lang` – and returns its ID.
/// `is_admin = None`: it only becomes administrator if it is the first account (initial setup).
pub(super) async fn create_user(
    state: &AppState,
    email: &str,
    name: &str,
    password: &str,
    is_admin: Option<bool>,
    lang: Lang,
) -> AppResult<String> {
    let email = normalize_email(email)?;
    check_password(password)?;
    let name = match name.trim() {
        "" => email.split('@').next().unwrap_or("").to_string(),
        n => n.chars().take(100).collect(),
    };

    let exists: Option<(String,)> = sqlx::query_as("SELECT id FROM users WHERE email = ?")
        .bind(&email)
        .fetch_optional(&state.db)
        .await?;
    if exists.is_some() {
        return Err(AppError::Conflict(tr!("auth.email_taken")));
    }

    let password = password.to_string();
    let hash = tokio::task::spawn_blocking(move || hash_password(&password)).await.map_err(anyhow::Error::from)??;
    let id = uuid::Uuid::new_v4().to_string();
    let now = time::to_db(time::now());
    let mut tx = state.db.begin().await?;
    // Initial setup: the first account becomes administrator. The check is part of the INSERT itself,
    // so two concurrent registrations don't both become administrator.
    sqlx::query(
        "INSERT INTO users (id, email, name, password_hash, created_at, updated_at, is_admin, language) \
         VALUES (?, ?, ?, ?, ?, ?, COALESCE(?, NOT EXISTS (SELECT 1 FROM users)), ?)",
    )
    .bind(&id)
    .bind(&email)
    .bind(&name)
    .bind(&hash)
    .bind(&now)
    .bind(&now)
    .bind(is_admin)
    .bind(lang.code())
    .execute(&mut *tx)
    .await?;
    // Every new user gets a default calendar.
    sqlx::query("INSERT INTO calendars (id, user_id, name, color, description, visible, created_at, updated_at) VALUES (?, ?, ?, '#6750A4', '', 1, ?, ?)")
        .bind(uuid::Uuid::new_v4().to_string())
        .bind(&id)
        .bind(tr_in!(lang, "calendar.default_name"))
        .bind(&now)
        .bind(&now)
        .execute(&mut *tx)
        .await?;
    super::tasks::ensure_list(&mut tx, &id).await?;
    crate::birthdays::ensure_calendar(&mut tx, &id, lang).await?;
    tx.commit().await?;
    crate::contacts::ensure_default_book(&state.db, &id).await?;
    Ok(id)
}

pub async fn login(State(state): State<AppState>, Json(input): Json<LoginInput>) -> AppResult<Json<AuthResponse>> {
    let email = input.email.trim().to_lowercase();
    let user = sqlx::query_as::<_, UserRow>("SELECT * FROM users WHERE email = ?")
        .bind(&email)
        .fetch_optional(&state.db)
        .await?;
    let password = input.password;
    // Hash check in the blocking pool, it is deliberately compute-intensive.
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
        // Only reveal that the account is disabled after the correct password
        Some(u) if ok && u.disabled => {
            tracing::warn!("Rejected sign-in to disabled account {}", u.id);
            Err(AppError::Forbidden(tr!("auth.account_disabled")))
        }
        Some(u) if ok => {
            if let Some(secret) = &u.totp_secret {
                match input.code.as_deref().map(str::trim).filter(|c| !c.is_empty()) {
                    None => return Err(AppError::TotpRequired(tr!("auth.totp_required"))),
                    Some(code) => crate::totp::verify_second_factor(&state, &u.id, secret, code).await?,
                }
            }
            touch_login(&state, &u.id).await?;
            tracing::info!("Sign-in: account {}", u.id);
            respond(&state, u)
        }
        Some(u) => {
            tracing::warn!("Failed sign-in for account {}: wrong password", u.id);
            Err(AppError::Unauthorized(tr!("auth.wrong_credentials")))
        }
        None => {
            tracing::warn!("Failed sign-in: unknown email address");
            Err(AppError::Unauthorized(tr!("auth.wrong_credentials")))
        }
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
            tracing::warn!("Account {}: rejected email/password change with wrong password", user.id);
            return Err(AppError::Unauthorized(tr!("auth.current_password_wrong")));
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
            return Err(AppError::Conflict(tr!("auth.email_taken")));
        }
    }
    let language = crate::i18n::parse_choice(input.language.as_deref())?;
    if let Some(v) = input.default_view.as_deref() {
        if !VIEW_MODES.contains(&v) {
            return Err(AppError::BadRequest(tr!("auth.invalid_default_view")));
        }
        default_view = v.to_string();
    }
    if wants_password {
        let pw = input.new_password.as_deref().unwrap();
        check_password(pw)?;
        hash = hash_password(pw)?;
    }

    let language_changed = language.is_some_and(|l| l.code() != user.language);
    let mut tx = state.db.begin().await?;
    sqlx::query(
        "UPDATE users SET name = ?, email = ?, password_hash = ?, default_view = ?, language = ?, updated_at = ? WHERE id = ?",
    )
    .bind(&name)
    .bind(&email)
    .bind(&hash)
    .bind(&default_view)
    .bind(language.map_or(user.language.as_str(), |l| l.code()))
    .bind(time::to_db(time::now()))
    .bind(&user.id)
    .execute(&mut *tx)
    .await?;
    if language_changed {
        // Birthday events carry texts in the user's language ("Born on …")
        let ids: Vec<(String,)> = sqlx::query_as(
            "SELECT c.id FROM contacts c JOIN addressbooks b ON b.id = c.addressbook_id WHERE b.user_id = ?",
        )
        .bind(&user.id)
        .fetch_all(&mut *tx)
        .await?;
        for (id,) in &ids {
            crate::birthdays::sync_contact(&mut tx, id).await?;
        }
        tracing::info!("Account {}: language changed to {}", user.id, language.unwrap().code());
    }
    tx.commit().await?;
    if wants_email {
        tracing::info!("Account {}: email address changed", user.id);
    }
    if wants_password {
        tracing::info!("Account {}: password changed", user.id);
    }
    Ok(Json(load_user(&state, &auth.id).await?.into()))
}
