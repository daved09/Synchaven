use axum::{
    extract::DefaultBodyLimit,
    routing::{get, post, put},
    Json, Router,
};
use serde_json::{json, Value};

use crate::{error::AppError, AppState};

mod admin;
mod auth;
mod calendars;
mod contacts;
mod events;
mod ical;
mod push;
mod security;
mod tasks;

pub fn api_router() -> Router<AppState> {
    Router::new()
        .route("/info", get(info))
        .route("/auth/register", post(auth::register))
        .route("/auth/login", post(auth::login))
        .route("/auth/me", get(auth::me).put(auth::update_me))
        .route("/auth/refresh", post(auth::refresh))
        .route("/auth/totp", get(security::status))
        .route("/auth/totp/setup", post(security::setup))
        .route("/auth/totp/enable", post(security::enable))
        .route("/auth/totp/disable", post(security::disable))
        .route("/auth/totp/recovery-codes", post(security::regenerate_recovery_codes))
        .route("/auth/app-passwords", get(security::list_app_passwords).post(security::create_app_password))
        .route("/auth/app-passwords/{id}", axum::routing::delete(security::delete_app_password))
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
        .route("/contact-categories/rename", post(contacts::rename_category))
        .route("/contact-categories/delete", post(contacts::delete_category))
        .route("/task-lists", get(tasks::list_lists).post(tasks::create_list))
        .route("/task-lists/{id}", put(tasks::update_list).delete(tasks::delete_list))
        .route("/task-lists/{id}/clear-completed", post(tasks::clear_completed))
        .route("/tasks", get(tasks::list).post(tasks::create))
        .route("/tasks/{id}", get(tasks::get).put(tasks::update).delete(tasks::delete))
        .route("/tasks/{id}/completed", put(tasks::set_completed))
        .route("/push/public-key", get(push::public_key))
        .route("/push/subscriptions", post(push::subscribe).delete(push::unsubscribe))
        .route("/push/test", post(push::test))
        .route("/admin/overview", get(admin::overview))
        .route("/admin/settings", get(admin::get_settings).put(admin::update_settings))
        .route("/admin/users", get(admin::list_users).post(admin::create))
        .route("/admin/users/{id}", put(admin::update).delete(admin::delete))
        .route("/admin/users/{id}/logout", post(admin::logout_everywhere))
        .route("/admin/logs", get(admin::logs).delete(admin::clear_logs))
        .route("/admin/export", post(admin::export))
        // Exports with many contact photos are considerably larger than the general limit
        .route("/admin/import", post(admin::import).layer(DefaultBodyLimit::max(512 * 1024 * 1024)))
        .fallback(|| async { AppError::NotFound })
}

async fn info(axum::extract::State(state): axum::extract::State<AppState>) -> Result<Json<Value>, AppError> {
    Ok(Json(json!({
        "name": "Synchaven",
        "version": env!("SYNCHAVEN_VERSION"),
        "api_version": 1,
        "allow_registration": auth::registration_open(&state).await?,
        // No account yet: the first one is created with the setup token from the server log
        "setup_required": crate::setup::pending(&state.db).await?,
    })))
}
