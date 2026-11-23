//! Verwaltung für Administratoren (`ADMIN_EMAILS`): Export und Import der ganzen Datenbank als JSON.

use axum::{
    body::Bytes,
    extract::{Query, State},
    http::header,
    response::IntoResponse,
    Json,
};
use serde::Deserialize;

use crate::{
    auth::AuthUser,
    backup::{self, ImportError, ImportReport},
    error::{AppError, AppResult},
    AppState,
};

async fn require_admin(state: &AppState, auth: &AuthUser) -> AppResult<()> {
    let email: String = sqlx::query_scalar("SELECT email FROM users WHERE id = ?")
        .bind(&auth.id)
        .fetch_one(&state.db)
        .await?;
    if !state.config.is_admin(&email) {
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
    // Zwischengespeicherte CardDAV-Anmeldungen gehören zu den alten Daten
    state.dav_auth.clear();
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
