//! Password hashing, JWT creation and the `AuthUser` extractor.

use crate::i18n::tr;
use argon2::{
    password_hash::{rand_core::OsRng, PasswordHash, PasswordHasher, PasswordVerifier, SaltString},
    Argon2,
};
use axum::{extract::FromRequestParts, http::request::Parts};
use chrono::{Duration, Utc};
use jsonwebtoken::{decode, encode, DecodingKey, EncodingKey, Header, Validation};
use serde::{Deserialize, Serialize};

use crate::{error::AppError, AppState};

pub struct JwtKeys {
    encoding: EncodingKey,
    decoding: DecodingKey,
}

#[derive(Debug, Serialize, Deserialize)]
struct Claims {
    sub: String,
    iat: i64,
    exp: i64,
}

impl JwtKeys {
    pub fn new(secret: &str) -> Self {
        Self {
            encoding: EncodingKey::from_secret(secret.as_bytes()),
            decoding: DecodingKey::from_secret(secret.as_bytes()),
        }
    }

    /// Issues a token that is valid for `ttl_days` days (admin setting).
    pub fn issue(&self, user_id: &str, ttl_days: i64) -> Result<(String, chrono::DateTime<Utc>), AppError> {
        let now = Utc::now();
        let exp = now + Duration::days(ttl_days);
        let claims = Claims { sub: user_id.to_string(), iat: now.timestamp(), exp: exp.timestamp() };
        let token = encode(&Header::default(), &claims, &self.encoding)
            .map_err(|e| AppError::Internal(e.into()))?;
        Ok((token, exp))
    }

    /// User ID and issue time (Unix seconds) of a valid token
    fn verify(&self, token: &str) -> Result<(String, i64), AppError> {
        decode::<Claims>(token, &self.decoding, &Validation::default())
            .map(|d| (d.claims.sub, d.claims.iat))
            .map_err(|_| AppError::Unauthorized(tr!("auth.token_invalid")))
    }
}

pub fn hash_password(password: &str) -> Result<String, AppError> {
    let salt = SaltString::generate(&mut OsRng);
    Argon2::default()
        .hash_password(password.as_bytes(), &salt)
        .map(|h| h.to_string())
        .map_err(|e| AppError::Internal(anyhow::anyhow!("Hashing failed: {e}")))
}

pub fn verify_password(password: &str, hash: &str) -> bool {
    PasswordHash::new(hash)
        .map(|parsed| Argon2::default().verify_password(password.as_bytes(), &parsed).is_ok())
        .unwrap_or(false)
}

/// Signed-in user, determined from `Authorization: Bearer <token>`.
pub struct AuthUser {
    pub id: String,
}

impl FromRequestParts<AppState> for AuthUser {
    type Rejection = AppError;

    async fn from_request_parts(parts: &mut Parts, state: &AppState) -> Result<Self, Self::Rejection> {
        let header = parts
            .headers
            .get(axum::http::header::AUTHORIZATION)
            .and_then(|v| v.to_str().ok())
            .ok_or_else(|| AppError::Unauthorized(tr!("auth.required")))?;
        let token = header
            .strip_prefix("Bearer ")
            .or_else(|| header.strip_prefix("bearer "))
            .ok_or_else(|| AppError::Unauthorized(tr!("auth.bearer_expected")))?;
        let (id, issued_at) = state.jwt.verify(token.trim())?;

        // Make sure the user still exists (e.g. after deletion), is not disabled
        // and the token has not been revoked by "sign out everywhere" or a password reset.
        let row: Option<(bool, Option<i64>)> =
            sqlx::query_as("SELECT disabled, tokens_valid_after FROM users WHERE id = ?")
                .bind(&id)
                .fetch_optional(&state.db)
                .await?;
        match row {
            None => Err(AppError::Unauthorized(tr!("auth.user_gone"))),
            Some((true, _)) => Err(AppError::Unauthorized(tr!("auth.account_disabled"))),
            Some((false, Some(after))) if issued_at < after => {
                Err(AppError::Unauthorized(tr!("auth.session_ended")))
            }
            Some(_) => Ok(AuthUser { id }),
        }
    }
}
