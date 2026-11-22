use axum::{
    routing::{get, post},
    Json, Router,
};
use serde_json::{json, Value};

use crate::{error::AppError, AppState};

mod auth;
mod calendars;
mod events;
mod ical;

pub fn api_router() -> Router<AppState> {
    Router::new()
        .route("/info", get(info))
        .route("/auth/register", post(auth::register))
        .route("/auth/login", post(auth::login))
        .route("/auth/me", get(auth::me).put(auth::update_me))
        .route("/calendars", get(calendars::list).post(calendars::create))
        .route(
            "/calendars/{id}",
            get(calendars::get).put(calendars::update).delete(calendars::delete),
        )
        .route("/calendars/{id}/export", get(ical::export))
        .route("/calendars/{id}/import", post(ical::import))
        .route("/events", get(events::list).post(events::create))
        .route("/events/{id}", get(events::get).put(events::update).delete(events::delete))
        .route("/events/{id}/exdates", post(events::add_exdate))
        .fallback(|| async { AppError::NotFound })
}

async fn info(axum::extract::State(state): axum::extract::State<AppState>) -> Json<Value> {
    Json(json!({
        "name": "Kalender",
        "version": env!("CARGO_PKG_VERSION"),
        "api_version": 1,
        "allow_registration": state.config.allow_registration,
    }))
}
