use axum::{
    routing::{get, post, put},
    Json, Router,
};
use serde_json::{json, Value};

use crate::{error::AppError, AppState};

mod auth;
mod calendars;
mod contacts;
mod events;
mod ical;
mod push;

pub fn api_router() -> Router<AppState> {
    Router::new()
        .route("/info", get(info))
        .route("/auth/register", post(auth::register))
        .route("/auth/login", post(auth::login))
        .route("/auth/me", get(auth::me).put(auth::update_me))
        .route("/auth/refresh", post(auth::refresh))
        .route("/calendars", get(calendars::list).post(calendars::create))
        .route(
            "/calendars/{id}",
            get(calendars::get).put(calendars::update).delete(calendars::delete),
        )
        .route("/calendars/{id}/events", get(events::list_series))
        .route("/calendars/{id}/export", get(ical::export))
        .route("/calendars/{id}/import", post(ical::import))
        .route("/events", get(events::list).post(events::create))
        .route("/events/{id}", get(events::get).put(events::update).delete(events::delete))
        .route("/events/{id}/exdates", post(events::add_exdate))
        .route("/addressbooks", get(contacts::list_books).post(contacts::create_book))
        .route("/addressbooks/{id}", put(contacts::update_book).delete(contacts::delete_book))
        .route("/addressbooks/{id}/export", get(contacts::export_book))
        .route("/addressbooks/{id}/import", post(contacts::import_book))
        .route("/contacts", get(contacts::list).post(contacts::create))
        .route("/contacts/{id}", get(contacts::get).put(contacts::update).delete(contacts::delete))
        .route("/push/public-key", get(push::public_key))
        .route("/push/subscriptions", post(push::subscribe).delete(push::unsubscribe))
        .route("/push/test", post(push::test))
        .fallback(|| async { AppError::NotFound })
}

async fn info(axum::extract::State(state): axum::extract::State<AppState>) -> Json<Value> {
    Json(json!({
        "name": "Synchaven",
        "version": env!("SYNCHAVEN_VERSION"),
        "api_version": 1,
        "allow_registration": state.config.allow_registration,
    }))
}
