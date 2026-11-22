use axum::{
    extract::{Path, Query, State},
    http::StatusCode,
    Json,
};
use chrono::{DateTime, Datelike, Duration, Utc};
use serde::Deserialize;
use sqlx::{QueryBuilder, Sqlite};

use super::calendars::{ensure_writable, owned_calendar};
use crate::{
    auth::AuthUser,
    error::{AppError, AppResult},
    birthdays,
    models::{Event, EventInput, EventRow, Occurrence},
    recurrence, time, AppState,
};

/// Validierte, speicherfertige Termindaten.
pub struct EventData {
    pub calendar_id: String,
    pub title: String,
    pub description: String,
    pub location: String,
    pub start: DateTime<Utc>,
    pub end: DateTime<Utc>,
    pub all_day: bool,
    pub timezone: String,
    pub rrule: Option<String>,
    pub exdates: Vec<DateTime<Utc>>,
}

impl EventData {
    pub fn validate(input: EventInput) -> AppResult<Self> {
        let title = input.title.trim().to_string();
        if title.is_empty() || title.chars().count() > 500 {
            return Err(AppError::BadRequest("Der Titel muss 1–500 Zeichen lang sein".into()));
        }
        let tz_name = input.timezone.as_deref().map(str::trim).filter(|t| !t.is_empty()).unwrap_or("UTC");
        let tz = time::parse_tz(tz_name)
            .ok_or_else(|| AppError::BadRequest(format!("Unbekannte Zeitzone: {tz_name}")))?;

        let (start, end) = if input.all_day {
            // Ganztägig: Mitternacht UTC, Ende exklusiv
            let s = time::midnight_utc(input.start.date_naive());
            let e = time::midnight_utc(input.end.date_naive());
            (s, if e <= s { s + Duration::days(1) } else { e })
        } else {
            if input.end < input.start {
                return Err(AppError::BadRequest("Das Ende darf nicht vor dem Beginn liegen".into()));
            }
            (input.start, input.end)
        };

        let rrule = match input.rrule.as_deref().map(str::trim).filter(|r| !r.is_empty()) {
            Some(r) => Some(recurrence::normalize(r, start, tz, input.all_day).map_err(AppError::BadRequest)?),
            None => None,
        };
        let mut exdates = if rrule.is_some() { input.exdates.unwrap_or_default() } else { vec![] };
        exdates.sort();
        exdates.dedup();

        Ok(Self {
            calendar_id: input.calendar_id,
            title,
            description: input.description.trim_end().to_string(),
            location: input.location.trim().to_string(),
            start,
            end,
            all_day: input.all_day,
            timezone: tz.name().to_string(),
            rrule,
            exdates,
        })
    }

    fn exdates_json(&self) -> String {
        serde_json::to_string(&self.exdates.iter().map(|d| time::to_db(*d)).collect::<Vec<_>>()).unwrap()
    }
}

/// Fügt einen Termin ein oder aktualisiert ihn anhand von (calendar_id, uid).
pub async fn upsert(state: &AppState, uid: &str, data: &EventData) -> AppResult<String> {
    let now = time::to_db(time::now());
    let (id,): (String,) = sqlx::query_as(
        "INSERT INTO events (id, calendar_id, uid, title, description, location, start_at, end_at, all_day, timezone, rrule, exdates, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT(calendar_id, uid) DO UPDATE SET
            title = excluded.title, description = excluded.description, location = excluded.location,
            start_at = excluded.start_at, end_at = excluded.end_at, all_day = excluded.all_day,
            timezone = excluded.timezone, rrule = excluded.rrule, exdates = excluded.exdates,
            updated_at = excluded.updated_at
         RETURNING id",
    )
    .bind(uuid::Uuid::new_v4().to_string())
    .bind(&data.calendar_id)
    .bind(uid)
    .bind(&data.title)
    .bind(&data.description)
    .bind(&data.location)
    .bind(time::to_db(data.start))
    .bind(time::to_db(data.end))
    .bind(data.all_day)
    .bind(&data.timezone)
    .bind(&data.rrule)
    .bind(data.exdates_json())
    .bind(&now)
    .bind(&now)
    .fetch_one(&state.db)
    .await?;
    Ok(id)
}

pub fn new_uid() -> String {
    format!("{}@synchaven", uuid::Uuid::new_v4())
}

async fn owned_event(state: &AppState, user_id: &str, id: &str) -> AppResult<EventRow> {
    sqlx::query_as::<_, EventRow>(
        "SELECT e.* FROM events e JOIN calendars c ON c.id = e.calendar_id WHERE e.id = ? AND c.user_id = ?",
    )
    .bind(id)
    .bind(user_id)
    .fetch_optional(&state.db)
    .await?
    .ok_or(AppError::NotFound)
}

/// Wie `owned_event`, aber nur für Termine, die von Hand bearbeitet werden dürfen.
async fn editable_event(state: &AppState, user_id: &str, id: &str) -> AppResult<EventRow> {
    let row = owned_event(state, user_id, id).await?;
    if row.contact_id.is_some() {
        return Err(AppError::BadRequest("Geburtstage werden über den Kontakt bearbeitet".into()));
    }
    Ok(row)
}

#[derive(Deserialize)]
pub struct ListQuery {
    start: DateTime<Utc>,
    end: DateTime<Utc>,
    /// Kommagetrennte Kalender-IDs (optional)
    calendar_ids: Option<String>,
    /// Volltextsuche in Titel, Beschreibung und Ort (optional)
    q: Option<String>,
}

/// Termine eines Zeitraums – wiederkehrende Termine werden in Einzelvorkommen expandiert.
pub async fn list(
    State(state): State<AppState>,
    auth: AuthUser,
    Query(q): Query<ListQuery>,
) -> AppResult<Json<Vec<Occurrence>>> {
    if q.end <= q.start {
        return Err(AppError::BadRequest("`end` muss nach `start` liegen".into()));
    }
    if q.end - q.start > Duration::days(800) {
        return Err(AppError::BadRequest("Der Zeitraum darf höchstens 800 Tage umfassen".into()));
    }
    let (from, to) = (time::to_db(q.start), time::to_db(q.end));

    let mut qb: QueryBuilder<Sqlite> = QueryBuilder::new(
        "SELECT e.* FROM events e JOIN calendars c ON c.id = e.calendar_id WHERE c.user_id = ",
    );
    qb.push_bind(&auth.id);
    qb.push(" AND e.start_at < ").push_bind(&to);
    qb.push(" AND (e.rrule IS NOT NULL OR e.end_at > ").push_bind(&from);
    qb.push(" OR e.start_at >= ").push_bind(&from).push(")");

    let ids: Vec<String> = q
        .calendar_ids
        .as_deref()
        .unwrap_or("")
        .split(',')
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(String::from)
        .collect();
    if !ids.is_empty() {
        qb.push(" AND e.calendar_id IN (");
        let mut sep = qb.separated(", ");
        for id in &ids {
            sep.push_bind(id);
        }
        qb.push(")");
    }
    if let Some(term) = q.q.as_deref().map(str::trim).filter(|t| !t.is_empty()) {
        let pattern = format!("%{}%", term.replace('\\', "\\\\").replace('%', "\\%").replace('_', "\\_"));
        qb.push(" AND (e.title LIKE ").push_bind(pattern.clone()).push(" ESCAPE '\\'");
        qb.push(" OR e.description LIKE ").push_bind(pattern.clone()).push(" ESCAPE '\\'");
        qb.push(" OR e.location LIKE ").push_bind(pattern).push(" ESCAPE '\\')");
    }

    let rows: Vec<EventRow> = qb.build_query_as().fetch_all(&state.db).await?;

    let mut out = Vec::new();
    for row in rows {
        let birth_year = row.birth_year;
        let ev: Event = row.into();
        let duration = ev.end - ev.start;
        let recurring = ev.rrule.is_some();
        for start in recurrence::occurrences(&ev, q.start, q.end) {
            out.push(Occurrence {
                id: if recurring { format!("{}_{}", ev.id, start.timestamp()) } else { ev.id.clone() },
                event_id: ev.id.clone(),
                calendar_id: ev.calendar_id.clone(),
                title: if ev.contact_id.is_some() {
                    birthdays::occurrence_title(&ev.title, birth_year, start.year())
                } else {
                    ev.title.clone()
                },
                description: ev.description.clone(),
                location: ev.location.clone(),
                start,
                end: start + duration,
                all_day: ev.all_day,
                timezone: ev.timezone.clone(),
                recurring,
                rrule: ev.rrule.clone(),
                contact_id: ev.contact_id.clone(),
                read_only: ev.read_only,
            });
        }
    }
    out.sort_by(|a, b| a.start.cmp(&b.start).then(b.all_day.cmp(&a.all_day)).then(a.title.cmp(&b.title)));
    Ok(Json(out))
}

pub async fn get(State(state): State<AppState>, auth: AuthUser, Path(id): Path<String>) -> AppResult<Json<Event>> {
    Ok(Json(owned_event(&state, &auth.id, &id).await?.into()))
}

pub async fn create(
    State(state): State<AppState>,
    auth: AuthUser,
    Json(input): Json<EventInput>,
) -> AppResult<(StatusCode, Json<Event>)> {
    let data = EventData::validate(input)?;
    ensure_writable(&owned_calendar(&state, &auth.id, &data.calendar_id).await?)?;
    let id = upsert(&state, &new_uid(), &data).await?;
    Ok((StatusCode::CREATED, Json(owned_event(&state, &auth.id, &id).await?.into())))
}

pub async fn update(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(id): Path<String>,
    Json(input): Json<EventInput>,
) -> AppResult<Json<Event>> {
    editable_event(&state, &auth.id, &id).await?;
    let data = EventData::validate(input)?;
    ensure_writable(&owned_calendar(&state, &auth.id, &data.calendar_id).await?)?;
    sqlx::query(
        "UPDATE events SET calendar_id = ?, title = ?, description = ?, location = ?, start_at = ?, end_at = ?,
         all_day = ?, timezone = ?, rrule = ?, exdates = ?, updated_at = ? WHERE id = ?",
    )
    .bind(&data.calendar_id)
    .bind(&data.title)
    .bind(&data.description)
    .bind(&data.location)
    .bind(time::to_db(data.start))
    .bind(time::to_db(data.end))
    .bind(data.all_day)
    .bind(&data.timezone)
    .bind(&data.rrule)
    .bind(data.exdates_json())
    .bind(time::to_db(time::now()))
    .bind(&id)
    .execute(&state.db)
    .await
    .map_err(|e| match &e {
        sqlx::Error::Database(db) if db.is_unique_violation() => {
            AppError::Conflict("Im Zielkalender existiert bereits ein Termin mit derselben UID".into())
        }
        _ => e.into(),
    })?;
    Ok(Json(owned_event(&state, &auth.id, &id).await?.into()))
}

pub async fn delete(State(state): State<AppState>, auth: AuthUser, Path(id): Path<String>) -> AppResult<StatusCode> {
    editable_event(&state, &auth.id, &id).await?;
    sqlx::query("DELETE FROM events WHERE id = ?").bind(&id).execute(&state.db).await?;
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Deserialize)]
pub struct ExdateInput {
    /// Startzeit des Vorkommens, das entfallen soll
    occurrence_start: DateTime<Utc>,
}

/// Entfernt ein einzelnes Vorkommen aus einer Serie (EXDATE).
pub async fn add_exdate(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(id): Path<String>,
    Json(input): Json<ExdateInput>,
) -> AppResult<Json<Event>> {
    let mut ev: Event = editable_event(&state, &auth.id, &id).await?.into();
    if ev.rrule.is_none() {
        return Err(AppError::BadRequest("Der Termin ist keine Serie".into()));
    }
    if !ev.exdates.contains(&input.occurrence_start) {
        ev.exdates.push(input.occurrence_start);
        ev.exdates.sort();
    }
    let json = serde_json::to_string(&ev.exdates.iter().map(|d| time::to_db(*d)).collect::<Vec<_>>()).unwrap();
    sqlx::query("UPDATE events SET exdates = ?, updated_at = ? WHERE id = ?")
        .bind(json)
        .bind(time::to_db(time::now()))
        .bind(&id)
        .execute(&state.db)
        .await?;
    Ok(Json(owned_event(&state, &auth.id, &id).await?.into()))
}
