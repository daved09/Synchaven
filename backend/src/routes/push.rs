//! Web-Push-Abonnements der Browser verwalten.

use std::time::Duration;

use axum::{extract::State, http::StatusCode, Json};
use serde::Deserialize;
use serde_json::{json, Value};

use crate::{
    auth::AuthUser,
    error::{AppError, AppResult},
    push::{Outcome, Subscription},
    time, AppState,
};

/// Öffentlicher VAPID-Schlüssel für `pushManager.subscribe({ applicationServerKey })`
pub async fn public_key(State(state): State<AppState>) -> Json<Value> {
    Json(json!({ "public_key": state.push.public_key }))
}

#[derive(Deserialize)]
pub struct Keys {
    p256dh: String,
    auth: String,
}

#[derive(Deserialize)]
pub struct SubscribeInput {
    endpoint: String,
    keys: Keys,
    /// IANA-Zeitzone des Browsers, z. B. `Europe/Berlin`
    timezone: Option<String>,
    #[serde(default)]
    user_agent: String,
}

/// Der Server ruft den Endpunkt selbst auf – nur HTTPS-Adressen öffentlicher Hosts zulassen.
fn validate_endpoint(endpoint: &str) -> AppResult<()> {
    let bad = || AppError::BadRequest("Ungültiger Push-Endpunkt".into());
    let url = reqwest::Url::parse(endpoint).map_err(|_| bad())?;
    if url.scheme() != "https" || endpoint.len() > 2000 {
        return Err(bad());
    }
    let host = url.host_str().unwrap_or("").to_ascii_lowercase();
    let is_ip = host.starts_with('[') || host.parse::<std::net::IpAddr>().is_ok();
    if is_ip || !host.contains('.') || host.ends_with(".local") || host.ends_with(".localhost") {
        return Err(bad());
    }
    Ok(())
}

pub async fn subscribe(
    State(state): State<AppState>,
    auth: AuthUser,
    Json(input): Json<SubscribeInput>,
) -> AppResult<StatusCode> {
    validate_endpoint(&input.endpoint)?;
    if input.keys.p256dh.len() > 200 || input.keys.auth.len() > 100 {
        return Err(AppError::BadRequest("Ungültige Schlüssel".into()));
    }
    let tz = input
        .timezone
        .as_deref()
        .and_then(time::parse_tz)
        .map(|t| t.name().to_string())
        .unwrap_or_else(|| "UTC".into());
    let ua: String = input.user_agent.chars().take(300).collect();
    let now = time::to_db(time::now());
    // Ein Browser kann den Benutzer wechseln: der Endpunkt gehört immer dem zuletzt angemeldeten.
    sqlx::query(
        "INSERT INTO push_subscriptions (id, user_id, endpoint, p256dh, auth, timezone, user_agent, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT(endpoint) DO UPDATE SET user_id = excluded.user_id, p256dh = excluded.p256dh,
            auth = excluded.auth, timezone = excluded.timezone, user_agent = excluded.user_agent,
            updated_at = excluded.updated_at",
    )
    .bind(uuid::Uuid::new_v4().to_string())
    .bind(&auth.id)
    .bind(&input.endpoint)
    .bind(input.keys.p256dh.trim())
    .bind(input.keys.auth.trim())
    .bind(tz)
    .bind(ua)
    .bind(&now)
    .bind(&now)
    .execute(&state.db)
    .await?;
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Deserialize)]
pub struct EndpointInput {
    endpoint: String,
}

pub async fn unsubscribe(
    State(state): State<AppState>,
    auth: AuthUser,
    Json(input): Json<EndpointInput>,
) -> AppResult<StatusCode> {
    sqlx::query("DELETE FROM push_subscriptions WHERE endpoint = ? AND user_id = ?")
        .bind(&input.endpoint)
        .bind(&auth.id)
        .execute(&state.db)
        .await?;
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Deserialize)]
pub struct TestInput {
    /// Nur an diesen Browser senden (sonst an alle des Benutzers)
    endpoint: Option<String>,
}

/// Sendet eine Testbenachrichtigung.
pub async fn test(
    State(state): State<AppState>,
    auth: AuthUser,
    Json(input): Json<TestInput>,
) -> AppResult<Json<Value>> {
    let subs: Vec<(String, String, String, String)> =
        sqlx::query_as("SELECT id, endpoint, p256dh, auth FROM push_subscriptions WHERE user_id = ?")
            .bind(&auth.id)
            .fetch_all(&state.db)
            .await?;
    let body = json!({
        "type": "test",
        "title": "Synchaven",
        "body": "Benachrichtigungen sind eingerichtet.",
    })
    .to_string();
    let mut sent = 0;
    for (id, endpoint, p256dh, key) in &subs {
        if input.endpoint.as_ref().is_some_and(|e| e != endpoint) {
            continue;
        }
        let target = Subscription { endpoint, p256dh, auth: key };
        match state.push.send(&target, body.as_bytes(), Duration::from_secs(300)).await {
            Ok(Outcome::Sent) => sent += 1,
            Ok(Outcome::Gone) => {
                sqlx::query("DELETE FROM push_subscriptions WHERE id = ?").bind(id).execute(&state.db).await?;
            }
            Err(e) => {
                tracing::warn!("Test-Push fehlgeschlagen: {e:#}");
                return Err(AppError::BadRequest(format!("Der Push-Dienst hat die Nachricht abgelehnt: {e}")));
            }
        }
    }
    if sent == 0 {
        return Err(AppError::BadRequest("Für diesen Browser sind keine Benachrichtigungen eingerichtet".into()));
    }
    Ok(Json(json!({ "sent": sent })))
}
