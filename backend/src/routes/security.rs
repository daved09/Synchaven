//! Two-factor authentication (TOTP) and app passwords of the signed-in account.

use axum::{
    extract::{Path, State},
    http::StatusCode,
    Json,
};
use base64::Engine;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

use crate::{
    auth::{verify_password, AuthUser},
    error::{AppError, AppResult},
    i18n::tr,
    models::UserRow,
    time, totp, AppState,
};

use super::auth::load_user;

/// Checks the account password again – changes to the sign-in need it, not only the session.
async fn require_password(user: &UserRow, password: Option<&str>) -> AppResult<()> {
    let (password, hash) = (password.unwrap_or("").to_string(), user.password_hash.clone());
    let ok = tokio::task::spawn_blocking(move || verify_password(&password, &hash)).await.unwrap_or(false);
    if !ok {
        tracing::warn!("Account {}: rejected security change with wrong password", user.id);
        return Err(AppError::Unauthorized(tr!("auth.current_password_wrong")));
    }
    Ok(())
}

/// Password and – with two-factor authentication on – a code of the second factor
async fn require_password_and_code(
    state: &AppState,
    user: &UserRow,
    password: Option<&str>,
    code: Option<&str>,
) -> AppResult<()> {
    require_password(user, password).await?;
    if let Some(secret) = &user.totp_secret {
        let code = code.map(str::trim).filter(|c| !c.is_empty());
        let Some(code) = code else { return Err(AppError::TotpRequired(tr!("auth.totp_required"))) };
        totp::verify_second_factor(state, &user.id, secret, code).await?;
    }
    Ok(())
}

// ---------- TOTP ----------

#[derive(Serialize)]
pub struct TotpStatus {
    enabled: bool,
    /// Unused recovery codes (0 if two-factor authentication is off)
    recovery_codes_left: usize,
}

/// `GET /api/auth/totp`
pub async fn status(State(state): State<AppState>, auth: AuthUser) -> AppResult<Json<TotpStatus>> {
    let (secret, codes): (Option<String>, String) =
        sqlx::query_as("SELECT totp_secret, totp_recovery_codes FROM users WHERE id = ?")
            .bind(&auth.id)
            .fetch_one(&state.db)
            .await?;
    Ok(Json(TotpStatus {
        enabled: secret.is_some(),
        recovery_codes_left: if secret.is_some() { totp::recovery_codes_left(&codes) } else { 0 },
    }))
}

#[derive(Deserialize)]
pub struct PasswordInput {
    current_password: Option<String>,
}

#[derive(Serialize)]
pub struct TotpSetup {
    /// Base32 secret for entering it by hand
    secret: String,
    /// `otpauth://` link – on phones it opens the authenticator app directly
    otpauth_uri: String,
    /// QR code of `otpauth_uri` as `data:image/svg+xml;base64,…`
    qr_code: String,
}

/// `POST /api/auth/totp/setup` – starts the setup with a new secret. Two-factor authentication is only
/// on after `enable` confirmed a code of it.
pub async fn setup(
    State(state): State<AppState>,
    auth: AuthUser,
    Json(input): Json<PasswordInput>,
) -> AppResult<Json<TotpSetup>> {
    let user = load_user(&state, &auth.id).await?;
    if user.totp_secret.is_some() {
        return Err(AppError::Conflict(tr!("auth.totp_already_enabled")));
    }
    require_password(&user, input.current_password.as_deref()).await?;
    let secret = totp::generate_secret();
    sqlx::query("UPDATE users SET totp_pending_secret = ? WHERE id = ?")
        .bind(&secret)
        .bind(&user.id)
        .execute(&state.db)
        .await?;
    let otpauth_uri = totp::otpauth_uri(&secret, &user.email);
    let svg = totp::qr_svg(&otpauth_uri)?;
    let qr_code = format!("data:image/svg+xml;base64,{}", base64::engine::general_purpose::STANDARD.encode(svg));
    Ok(Json(TotpSetup { secret, otpauth_uri, qr_code }))
}

#[derive(Deserialize)]
pub struct EnableInput {
    code: String,
}

#[derive(Serialize)]
pub struct RecoveryCodes {
    /// Shown only this once; each code replaces one code of the authenticator app once
    recovery_codes: Vec<String>,
}

/// `POST /api/auth/totp/enable` – confirms the setup with a code of the app
pub async fn enable(
    State(state): State<AppState>,
    auth: AuthUser,
    Json(input): Json<EnableInput>,
) -> AppResult<Json<RecoveryCodes>> {
    let (active, pending): (Option<String>, Option<String>) =
        sqlx::query_as("SELECT totp_secret, totp_pending_secret FROM users WHERE id = ?")
            .bind(&auth.id)
            .fetch_one(&state.db)
            .await?;
    if active.is_some() {
        return Err(AppError::Conflict(tr!("auth.totp_already_enabled")));
    }
    let Some(secret) = pending else { return Err(AppError::BadRequest(tr!("auth.totp_no_setup"))) };
    let Some(step) = totp::matching_step(&secret, &input.code, totp::current_step(), None) else {
        return Err(AppError::TotpInvalid(tr!("auth.totp_invalid")));
    };
    let (codes, hashes) = totp::generate_recovery_codes();
    let res = sqlx::query(
        "UPDATE users SET totp_secret = totp_pending_secret, totp_pending_secret = NULL, totp_last_step = ?, \
         totp_recovery_codes = ?, updated_at = ? WHERE id = ? AND totp_pending_secret = ?",
    )
    .bind(step)
    .bind(&hashes)
    .bind(time::to_db(time::now()))
    .bind(&auth.id)
    .bind(&secret)
    .execute(&state.db)
    .await?;
    if res.rows_affected() == 0 {
        return Err(AppError::BadRequest(tr!("auth.totp_no_setup")));
    }
    tracing::info!("Account {}: two-factor authentication enabled", auth.id);
    Ok(Json(RecoveryCodes { recovery_codes: codes }))
}

#[derive(Deserialize)]
pub struct PasswordCodeInput {
    current_password: Option<String>,
    code: Option<String>,
}

/// `POST /api/auth/totp/disable` – needs the password and a code (or a recovery code)
pub async fn disable(
    State(state): State<AppState>,
    auth: AuthUser,
    Json(input): Json<PasswordCodeInput>,
) -> AppResult<StatusCode> {
    let user = load_user(&state, &auth.id).await?;
    if user.totp_secret.is_none() {
        return Err(AppError::BadRequest(tr!("auth.totp_not_enabled")));
    }
    require_password_and_code(&state, &user, input.current_password.as_deref(), input.code.as_deref()).await?;
    disable_for(&state, &user.id).await?;
    tracing::info!("Account {}: two-factor authentication disabled", user.id);
    Ok(StatusCode::NO_CONTENT)
}

/// Turns two-factor authentication off (also used by the admin page)
pub(super) async fn disable_for(state: &AppState, user_id: &str) -> AppResult<()> {
    sqlx::query(
        "UPDATE users SET totp_secret = NULL, totp_pending_secret = NULL, totp_last_step = NULL, \
         totp_recovery_codes = '[]', updated_at = ? WHERE id = ?",
    )
    .bind(time::to_db(time::now()))
    .bind(user_id)
    .execute(&state.db)
    .await?;
    Ok(())
}

/// `POST /api/auth/totp/recovery-codes` – new recovery codes; the old ones become invalid
pub async fn regenerate_recovery_codes(
    State(state): State<AppState>,
    auth: AuthUser,
    Json(input): Json<PasswordCodeInput>,
) -> AppResult<Json<RecoveryCodes>> {
    let user = load_user(&state, &auth.id).await?;
    if user.totp_secret.is_none() {
        return Err(AppError::BadRequest(tr!("auth.totp_not_enabled")));
    }
    require_password_and_code(&state, &user, input.current_password.as_deref(), input.code.as_deref()).await?;
    let (codes, hashes) = totp::generate_recovery_codes();
    sqlx::query("UPDATE users SET totp_recovery_codes = ? WHERE id = ?")
        .bind(&hashes)
        .bind(&user.id)
        .execute(&state.db)
        .await?;
    tracing::info!("Account {}: new recovery codes", user.id);
    Ok(Json(RecoveryCodes { recovery_codes: codes }))
}

// ---------- App passwords ----------

#[derive(sqlx::FromRow)]
struct AppPasswordRow {
    id: String,
    name: String,
    created_at: String,
    last_used_at: Option<String>,
}

#[derive(Serialize)]
pub struct AppPassword {
    id: String,
    name: String,
    created_at: DateTime<Utc>,
    last_used_at: Option<DateTime<Utc>>,
    /// Only in the response to creating it
    #[serde(skip_serializing_if = "Option::is_none")]
    password: Option<String>,
}

impl From<AppPasswordRow> for AppPassword {
    fn from(r: AppPasswordRow) -> Self {
        Self {
            id: r.id,
            name: r.name,
            created_at: time::from_db(&r.created_at),
            last_used_at: r.last_used_at.as_deref().map(time::from_db),
            password: None,
        }
    }
}

/// `GET /api/auth/app-passwords` – newest first
pub async fn list_app_passwords(State(state): State<AppState>, auth: AuthUser) -> AppResult<Json<Vec<AppPassword>>> {
    let rows = sqlx::query_as::<_, AppPasswordRow>(
        "SELECT id, name, created_at, last_used_at FROM app_passwords WHERE user_id = ? ORDER BY created_at DESC, rowid DESC",
    )
    .bind(&auth.id)
    .fetch_all(&state.db)
    .await?;
    Ok(Json(rows.into_iter().map(Into::into).collect()))
}

#[derive(Deserialize)]
pub struct CreateAppPasswordInput {
    name: String,
    current_password: Option<String>,
}

const MAX_APP_PASSWORDS: i64 = 50;

/// `POST /api/auth/app-passwords` – the password is in the response only
pub async fn create_app_password(
    State(state): State<AppState>,
    auth: AuthUser,
    Json(input): Json<CreateAppPasswordInput>,
) -> AppResult<(StatusCode, Json<AppPassword>)> {
    let user = load_user(&state, &auth.id).await?;
    let name: String = input.name.trim().chars().take(100).collect();
    if name.is_empty() {
        return Err(AppError::BadRequest(tr!("auth.app_password_name_empty")));
    }
    require_password(&user, input.current_password.as_deref()).await?;
    let (count,): (i64,) =
        sqlx::query_as("SELECT COUNT(*) FROM app_passwords WHERE user_id = ?").bind(&user.id).fetch_one(&state.db).await?;
    if count >= MAX_APP_PASSWORDS {
        return Err(AppError::BadRequest(tr!("auth.app_password_limit", max = MAX_APP_PASSWORDS)));
    }
    let (password, hash) = totp::generate_app_password();
    let id = uuid::Uuid::new_v4().to_string();
    let now = time::now();
    sqlx::query("INSERT INTO app_passwords (id, user_id, name, password_hash, created_at) VALUES (?, ?, ?, ?, ?)")
        .bind(&id)
        .bind(&user.id)
        .bind(&name)
        .bind(&hash)
        .bind(time::to_db(now))
        .execute(&state.db)
        .await?;
    tracing::info!("Account {}: app password {id} created", user.id);
    Ok((
        StatusCode::CREATED,
        Json(AppPassword { id, name, created_at: now, last_used_at: None, password: Some(password) }),
    ))
}

/// `DELETE /api/auth/app-passwords/{id}` – CardDAV clients using it can no longer sign in
pub async fn delete_app_password(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(id): Path<String>,
) -> AppResult<StatusCode> {
    let res = sqlx::query("DELETE FROM app_passwords WHERE id = ? AND user_id = ?")
        .bind(&id)
        .bind(&auth.id)
        .execute(&state.db)
        .await?;
    if res.rows_affected() == 0 {
        return Err(AppError::NotFound);
    }
    tracing::info!("Account {}: app password {id} revoked", auth.id);
    Ok(StatusCode::NO_CONTENT)
}
