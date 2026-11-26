use crate::i18n::tr;
use axum::{
    extract::{Path, State},
    http::StatusCode,
    Json,
};

use crate::{
    auth::AuthUser,
    error::{AppError, AppResult},
    birthdays,
    i18n,
    models::{Calendar, CalendarInput, CalendarRow},
    reminders, subscriptions, time, AppState,
};

/// Events in this calendar are generated automatically and must not be changed by hand.
pub fn ensure_writable(cal: &CalendarRow) -> AppResult<()> {
    if cal.kind == birthdays::KIND {
        return Err(AppError::BadRequest(tr!("calendar.birthdays_read_only")));
    }
    if cal.kind == subscriptions::KIND {
        return Err(AppError::BadRequest(tr!("calendar.subscription_read_only")));
    }
    Ok(())
}

/// Loads a calendar if it belongs to the user.
pub async fn owned_calendar(state: &AppState, user_id: &str, id: &str) -> AppResult<CalendarRow> {
    sqlx::query_as::<_, CalendarRow>("SELECT * FROM calendars WHERE id = ? AND user_id = ?")
        .bind(id)
        .bind(user_id)
        .fetch_optional(&state.db)
        .await?
        .ok_or(AppError::NotFound)
}

/// Validate default reminders; `None` (field missing) leaves the existing ones unchanged.
fn validate_defaults(input: &CalendarInput) -> AppResult<(Option<String>, Option<String>)> {
    let check = |list: &Option<Vec<i32>>, all_day| -> AppResult<Option<String>> {
        list.clone()
            .map(|l| reminders::validate(l, all_day).map(|v| reminders::to_json(&v)).map_err(AppError::BadRequest))
            .transpose()
    };
    Ok((check(&input.default_reminders, false)?, check(&input.default_all_day_reminders, true)?))
}

fn validate(input: &CalendarInput) -> AppResult<(String, Option<String>)> {
    let name = validate_name(&input.name)?;
    Ok((name, parse_color(input.color.as_deref())?))
}

fn validate_name(name: &str) -> AppResult<String> {
    let name = name.trim();
    if name.is_empty() || name.chars().count() > 100 {
        return Err(AppError::BadRequest(tr!("validation.name_length")));
    }
    Ok(name.to_string())
}

/// Fetches a new subscription's file before anything is stored, so a wrong address is reported right away.
async fn fetch_new(state: &AppState, source_url: &str) -> AppResult<(reqwest::Url, subscriptions::Feed)> {
    let lang = i18n::current();
    let url = subscriptions::normalize_url(source_url).map_err(|e| AppError::BadRequest(e.message(lang)))?;
    let feed = state.subscriptions.fetch(&url).await.map_err(|e| AppError::BadRequest(e.message(lang)))?;
    // Checks that it is a calendar at all
    subscriptions::events_of(&feed, "").map_err(|e| AppError::BadRequest(e.message(lang)))?;
    Ok((url, feed))
}

/// Subscribes to a calendar file: the name may be empty, it is then taken from the file (or the host).
async fn create_subscription(state: &AppState, user_id: &str, input: &CalendarInput, source_url: &str) -> AppResult<String> {
    let color = parse_color(input.color.as_deref())?;
    let (defaults, all_day_defaults) = validate_defaults(input)?;
    let (url, feed) = fetch_new(state, source_url).await?;
    let name = match input.name.trim() {
        "" => subscriptions::feed_name(&feed).filter(|n| !n.is_empty()).unwrap_or_else(|| url.host_str().unwrap_or_default().to_string()),
        n => n.to_string(),
    };
    let name = validate_name(&name)?;
    let id = uuid::Uuid::new_v4().to_string();
    let events = subscriptions::events_of(&feed, &id).map_err(|e| AppError::BadRequest(e.message(i18n::current())))?;
    let now = time::to_db(time::now());
    let mut tx = state.db.begin().await?;
    sqlx::query(
        "INSERT INTO calendars (id, user_id, name, color, description, visible, kind, default_reminders,
         default_all_day_reminders, source_url, synced_at, sync_attempted_at, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
    )
    .bind(&id)
    .bind(user_id)
    .bind(&name)
    .bind(color.unwrap_or_else(|| "#6750A4".into()))
    .bind(input.description.trim())
    .bind(input.visible.unwrap_or(true))
    .bind(subscriptions::KIND)
    // Without a choice no reminders: nobody wants to be woken for every public holiday
    .bind(defaults.unwrap_or_else(|| reminders::to_json(&[])))
    .bind(all_day_defaults.unwrap_or_else(|| reminders::to_json(&[])))
    .bind(url.as_str())
    .bind(&now)
    .bind(&now)
    .bind(&now)
    .bind(&now)
    .execute(&mut *tx)
    .await?;
    subscriptions::apply(&mut tx, &id, &events).await?;
    tx.commit().await?;
    tracing::info!("Account {user_id} subscribed to a calendar ({id}) with {} event(s)", events.len());
    Ok(id)
}

/// Color in the format `#RRGGBB` (upper case); `None` if missing or empty.
pub fn parse_color(color: Option<&str>) -> AppResult<Option<String>> {
    match color.map(str::trim) {
        None | Some("") => Ok(None),
        Some(c) if c.len() == 7 && c.starts_with('#') && c[1..].chars().all(|ch| ch.is_ascii_hexdigit()) => {
            Ok(Some(c.to_uppercase()))
        }
        Some(_) => Err(AppError::BadRequest(tr!("validation.color_format"))),
    }
}

pub async fn list(State(state): State<AppState>, auth: AuthUser) -> AppResult<Json<Vec<Calendar>>> {
    let mut conn = state.db.acquire().await?;
    let lang = crate::i18n::user_lang(&mut conn, &auth.id).await?;
    birthdays::ensure_calendar(&mut conn, &auth.id, lang).await?;
    drop(conn);
    let rows = sqlx::query_as::<_, CalendarRow>("SELECT * FROM calendars WHERE user_id = ? ORDER BY created_at, name")
        .bind(&auth.id)
        .fetch_all(&state.db)
        .await?;
    Ok(Json(rows.into_iter().map(Into::into).collect()))
}

pub async fn get(State(state): State<AppState>, auth: AuthUser, Path(id): Path<String>) -> AppResult<Json<Calendar>> {
    Ok(Json(owned_calendar(&state, &auth.id, &id).await?.into()))
}

pub async fn create(
    State(state): State<AppState>,
    auth: AuthUser,
    Json(input): Json<CalendarInput>,
) -> AppResult<(StatusCode, Json<Calendar>)> {
    if let Some(source_url) = input.source_url.as_deref().filter(|u| !u.trim().is_empty()) {
        let id = create_subscription(&state, &auth.id, &input, source_url).await?;
        return Ok((StatusCode::CREATED, Json(owned_calendar(&state, &auth.id, &id).await?.into())));
    }
    let (name, color) = validate(&input)?;
    let (defaults, all_day_defaults) = validate_defaults(&input)?;
    let id = uuid::Uuid::new_v4().to_string();
    let now = time::to_db(time::now());
    sqlx::query("INSERT INTO calendars (id, user_id, name, color, description, visible, default_reminders, default_all_day_reminders, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
        .bind(&id)
        .bind(&auth.id)
        .bind(&name)
        .bind(color.unwrap_or_else(|| "#6750A4".into()))
        .bind(input.description.trim())
        .bind(input.visible.unwrap_or(true))
        .bind(defaults.unwrap_or_else(|| reminders::to_json(reminders::DEFAULT_TIMED)))
        .bind(all_day_defaults.unwrap_or_else(|| reminders::to_json(reminders::DEFAULT_ALL_DAY)))
        .bind(&now)
        .bind(&now)
        .execute(&state.db)
        .await?;
    Ok((StatusCode::CREATED, Json(owned_calendar(&state, &auth.id, &id).await?.into())))
}

pub async fn update(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(id): Path<String>,
    Json(input): Json<CalendarInput>,
) -> AppResult<Json<Calendar>> {
    let existing = owned_calendar(&state, &auth.id, &id).await?;
    let (name, color) = validate(&input)?;
    let (defaults, all_day_defaults) = validate_defaults(&input)?;
    // A new address of a subscription: fetch it first, so a wrong one leaves the calendar as it is
    let new_source = match input.source_url.as_deref().map(str::trim) {
        Some(u) if existing.kind == subscriptions::KIND && !u.is_empty() => {
            let url = subscriptions::normalize_url(u).map_err(|e| AppError::BadRequest(e.message(i18n::current())))?;
            (existing.source_url.as_deref() != Some(url.as_str())).then_some(u)
        }
        _ => None,
    };
    let new_source = match new_source {
        Some(u) => {
            let (url, feed) = fetch_new(&state, u).await?;
            Some((url, subscriptions::events_of(&feed, &id).map_err(|e| AppError::BadRequest(e.message(i18n::current())))?))
        }
        None => None,
    };
    sqlx::query(
        "UPDATE calendars SET name = ?, color = ?, description = ?, visible = ?, default_reminders = ?,
         default_all_day_reminders = ?, updated_at = ? WHERE id = ?",
    )
        .bind(&name)
        .bind(color.unwrap_or(existing.color))
        .bind(input.description.trim())
        .bind(input.visible.unwrap_or(existing.visible))
        .bind(defaults.unwrap_or(existing.default_reminders))
        .bind(all_day_defaults.unwrap_or(existing.default_all_day_reminders))
        .bind(time::to_db(time::now()))
        .bind(&id)
        .execute(&state.db)
        .await?;
    if let Some((url, events)) = new_source {
        let now = time::to_db(time::now());
        let mut tx = state.db.begin().await?;
        subscriptions::apply(&mut tx, &id, &events).await?;
        sqlx::query(
            "UPDATE calendars SET source_url = ?, synced_at = ?, sync_attempted_at = ?, sync_error = NULL WHERE id = ?",
        )
        .bind(url.as_str())
        .bind(&now)
        .bind(&now)
        .bind(&id)
        .execute(&mut *tx)
        .await?;
        tx.commit().await?;
    }
    Ok(Json(owned_calendar(&state, &auth.id, &id).await?.into()))
}

/// Fetches a subscribed calendar now. A failed fetch is not an error of the request: the calendar is returned with
/// `sync_error` set (and keeps its previous events).
pub async fn refresh(State(state): State<AppState>, auth: AuthUser, Path(id): Path<String>) -> AppResult<Json<Calendar>> {
    let cal = owned_calendar(&state, &auth.id, &id).await?;
    if cal.kind != subscriptions::KIND {
        return Err(AppError::BadRequest(tr!("calendar.not_subscription")));
    }
    subscriptions::refresh(&state, &id, i18n::current()).await?;
    Ok(Json(owned_calendar(&state, &auth.id, &id).await?.into()))
}

pub async fn delete(State(state): State<AppState>, auth: AuthUser, Path(id): Path<String>) -> AppResult<StatusCode> {
    let cal = owned_calendar(&state, &auth.id, &id).await?;
    if cal.kind == birthdays::KIND {
        return Err(AppError::BadRequest(tr!("calendar.birthdays_not_deletable")));
    }
    // Events are deleted along with it via ON DELETE CASCADE.
    sqlx::query("DELETE FROM calendars WHERE id = ?").bind(&id).execute(&state.db).await?;
    tracing::info!("Calendar \"{}\" ({id}) deleted by account {}", cal.name, auth.id);
    Ok(StatusCode::NO_CONTENT)
}
