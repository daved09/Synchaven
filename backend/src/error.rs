use axum::{
    http::StatusCode,
    response::{IntoResponse, Response},
    Json,
};
use crate::i18n::tr;
use serde_json::json;

#[derive(Debug, thiserror::Error)]
pub enum AppError {
    #[error("{0}")]
    BadRequest(String),
    #[error("{0}")]
    Unauthorized(String),
    #[error("{0}")]
    Forbidden(String),
    #[error("Not found")]
    NotFound,
    #[error("{0}")]
    Conflict(String),
    /// Correct password, but the account needs a code of the authenticator app (or a recovery code)
    #[error("{0}")]
    TotpRequired(String),
    /// The code of the second factor is wrong or was already used
    #[error("{0}")]
    TotpInvalid(String),
    #[error("{0}")]
    TooManyRequests(String),
    #[error("Internal error")]
    Internal(#[from] anyhow::Error),
}

pub type AppResult<T> = Result<T, AppError>;

impl From<sqlx::Error> for AppError {
    fn from(e: sqlx::Error) -> Self {
        match e {
            sqlx::Error::RowNotFound => AppError::NotFound,
            other => AppError::Internal(other.into()),
        }
    }
}

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        let (status, code) = match &self {
            AppError::BadRequest(_) => (StatusCode::BAD_REQUEST, "bad_request"),
            AppError::Unauthorized(_) => (StatusCode::UNAUTHORIZED, "unauthorized"),
            AppError::Forbidden(_) => (StatusCode::FORBIDDEN, "forbidden"),
            AppError::NotFound => (StatusCode::NOT_FOUND, "not_found"),
            AppError::Conflict(_) => (StatusCode::CONFLICT, "conflict"),
            AppError::TotpRequired(_) => (StatusCode::UNAUTHORIZED, "totp_required"),
            AppError::TotpInvalid(_) => (StatusCode::UNAUTHORIZED, "totp_invalid"),
            AppError::TooManyRequests(_) => (StatusCode::TOO_MANY_REQUESTS, "too_many_requests"),
            AppError::Internal(e) => {
                tracing::error!("Internal error: {e:#}");
                (StatusCode::INTERNAL_SERVER_ERROR, "internal")
            }
        };
        let message = match self {
            AppError::NotFound => tr!("error.not_found"),
            AppError::Internal(_) => tr!("error.internal"),
            other => other.to_string(),
        };
        (status, Json(json!({ "error": code, "message": message }))).into_response()
    }
}
