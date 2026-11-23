use axum::{
    extract::{Path, State},
    http::StatusCode,
    Json,
};

use crate::{
    auth::AuthUser,
    error::{AppError, AppResult},
    birthdays,
    models::{Calendar, CalendarInput, CalendarRow},
    reminders, time, AppState,
};

/// Termine in diesem Kalender werden automatisch erzeugt und dürfen nicht von Hand geändert werden.
pub fn ensure_writable(cal: &CalendarRow) -> AppResult<()> {
    if cal.kind == birthdays::KIND {
        return Err(AppError::BadRequest(
            "Der Geburtstagskalender wird automatisch aus den Kontakten erstellt".into(),
        ));
    }
    Ok(())
}

/// Lädt einen Kalender, sofern er dem Benutzer gehört.
pub async fn owned_calendar(state: &AppState, user_id: &str, id: &str) -> AppResult<CalendarRow> {
    sqlx::query_as::<_, CalendarRow>("SELECT * FROM calendars WHERE id = ? AND user_id = ?")
        .bind(id)
        .bind(user_id)
        .fetch_optional(&state.db)
        .await?
        .ok_or(AppError::NotFound)
}

/// Standard-Erinnerungen prüfen; `None` (Feld fehlt) lässt die bisherigen unverändert.
fn validate_defaults(input: &CalendarInput) -> AppResult<(Option<String>, Option<String>)> {
    let check = |list: &Option<Vec<i32>>, all_day| -> AppResult<Option<String>> {
        list.clone()
            .map(|l| reminders::validate(l, all_day).map(|v| reminders::to_json(&v)).map_err(AppError::BadRequest))
            .transpose()
    };
    Ok((check(&input.default_reminders, false)?, check(&input.default_all_day_reminders, true)?))
}

fn validate(input: &CalendarInput) -> AppResult<(String, Option<String>)> {
    let name = input.name.trim();
    if name.is_empty() || name.chars().count() > 100 {
        return Err(AppError::BadRequest("Der Name muss 1–100 Zeichen lang sein".into()));
    }
    let color = match input.color.as_deref().map(str::trim) {
        None | Some("") => None,
        Some(c) if c.len() == 7 && c.starts_with('#') && c[1..].chars().all(|ch| ch.is_ascii_hexdigit()) => {
            Some(c.to_uppercase())
        }
        Some(_) => return Err(AppError::BadRequest("Farbe muss im Format #RRGGBB angegeben werden".into())),
    };
    Ok((name.to_string(), color))
}

pub async fn list(State(state): State<AppState>, auth: AuthUser) -> AppResult<Json<Vec<Calendar>>> {
    birthdays::ensure_calendar(&mut *state.db.acquire().await?, &auth.id).await?;
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
    Ok(Json(owned_calendar(&state, &auth.id, &id).await?.into()))
}

pub async fn delete(State(state): State<AppState>, auth: AuthUser, Path(id): Path<String>) -> AppResult<StatusCode> {
    let cal = owned_calendar(&state, &auth.id, &id).await?;
    if cal.kind == birthdays::KIND {
        return Err(AppError::BadRequest(
            "Der Geburtstagskalender kann nicht gelöscht, aber ausgeblendet werden".into(),
        ));
    }
    // Termine werden per ON DELETE CASCADE mitgelöscht.
    sqlx::query("DELETE FROM calendars WHERE id = ?").bind(&id).execute(&state.db).await?;
    Ok(StatusCode::NO_CONTENT)
}
