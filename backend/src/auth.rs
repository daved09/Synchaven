//! Passwort-Hashing, JWT-Erzeugung und der `AuthUser`-Extractor.

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
    ttl_days: i64,
}

#[derive(Debug, Serialize, Deserialize)]
struct Claims {
    sub: String,
    iat: i64,
    exp: i64,
}

impl JwtKeys {
    pub fn new(secret: &str, ttl_days: i64) -> Self {
        Self {
            encoding: EncodingKey::from_secret(secret.as_bytes()),
            decoding: DecodingKey::from_secret(secret.as_bytes()),
            ttl_days,
        }
    }

    pub fn issue(&self, user_id: &str) -> Result<(String, chrono::DateTime<Utc>), AppError> {
        let now = Utc::now();
        let exp = now + Duration::days(self.ttl_days);
        let claims = Claims { sub: user_id.to_string(), iat: now.timestamp(), exp: exp.timestamp() };
        let token = encode(&Header::default(), &claims, &self.encoding)
            .map_err(|e| AppError::Internal(e.into()))?;
        Ok((token, exp))
    }

    fn verify(&self, token: &str) -> Result<String, AppError> {
        decode::<Claims>(token, &self.decoding, &Validation::default())
            .map(|d| d.claims.sub)
            .map_err(|_| AppError::Unauthorized("Token ungültig oder abgelaufen".into()))
    }
}

pub fn hash_password(password: &str) -> Result<String, AppError> {
    let salt = SaltString::generate(&mut OsRng);
    Argon2::default()
        .hash_password(password.as_bytes(), &salt)
        .map(|h| h.to_string())
        .map_err(|e| AppError::Internal(anyhow::anyhow!("Hashing fehlgeschlagen: {e}")))
}

pub fn verify_password(password: &str, hash: &str) -> bool {
    PasswordHash::new(hash)
        .map(|parsed| Argon2::default().verify_password(password.as_bytes(), &parsed).is_ok())
        .unwrap_or(false)
}

/// Angemeldeter Benutzer, ermittelt aus `Authorization: Bearer <token>`.
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
            .ok_or_else(|| AppError::Unauthorized("Anmeldung erforderlich".into()))?;
        let token = header
            .strip_prefix("Bearer ")
            .or_else(|| header.strip_prefix("bearer "))
            .ok_or_else(|| AppError::Unauthorized("Bearer-Token erwartet".into()))?;
        let id = state.jwt.verify(token.trim())?;

        // Sicherstellen, dass der Benutzer noch existiert (z. B. nach Löschung).
        let exists: Option<(String,)> = sqlx::query_as("SELECT id FROM users WHERE id = ?")
            .bind(&id)
            .fetch_optional(&state.db)
            .await?;
        if exists.is_none() {
            return Err(AppError::Unauthorized("Benutzer existiert nicht mehr".into()));
        }
        Ok(AuthUser { id })
    }
}
