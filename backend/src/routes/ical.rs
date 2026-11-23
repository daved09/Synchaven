use axum::{
    extract::{Path, Query, State},
    http::header,
    response::IntoResponse,
    Json,
};
use serde::Deserialize;
use serde_json::{json, Value};

use super::{
    calendars::{ensure_writable, owned_calendar},
    events::{new_uid, upsert, EventData},
};
use crate::{
    auth::AuthUser,
    error::{AppError, AppResult},
    ical,
    models::{Event, EventInput, EventRow},
    reminders, time, AppState,
};

pub async fn export(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(id): Path<String>,
) -> AppResult<impl IntoResponse> {
    let cal = owned_calendar(&state, &auth.id, &id).await?;
    let rows = sqlx::query_as::<_, EventRow>("SELECT * FROM events WHERE calendar_id = ? ORDER BY start_at")
        .bind(&id)
        .fetch_all(&state.db)
        .await?;
    let events: Vec<Event> = rows.into_iter().map(Into::into).collect();
    let body = ical::export(&cal.name, &events, |ev| reminders::effective(ev, &cal));
    let filename: String = cal
        .name
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() || c == '-' || c == '_' { c } else { '_' })
        .collect();
    Ok((
        [
            (header::CONTENT_TYPE, "text/calendar; charset=utf-8".to_string()),
            (header::CONTENT_DISPOSITION, format!("attachment; filename=\"{filename}.ics\"")),
        ],
        body,
    ))
}

#[derive(Deserialize)]
pub struct ImportQuery {
    /// Zeitzone für Zeitangaben ohne TZID (z. B. `Europe/Berlin`)
    timezone: Option<String>,
}

/// Importiert eine .ics-Datei (Request-Body als Text). Vorhandene Termine mit
/// gleicher UID werden aktualisiert.
pub async fn import(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(id): Path<String>,
    Query(q): Query<ImportQuery>,
    body: String,
) -> AppResult<Json<Value>> {
    ensure_writable(&owned_calendar(&state, &auth.id, &id).await?)?;
    let default_tz = q.timezone.as_deref().and_then(time::parse_tz).unwrap_or(chrono_tz::UTC);
    let parsed = ical::parse(&body, default_tz).map_err(AppError::BadRequest)?;

    let (masters, overrides): (Vec<_>, Vec<_>) = parsed.into_iter().partition(|e| e.recurrence_id.is_none());
    let mut imported = 0usize;
    let mut skipped = Vec::new();

    let to_input = |e: &ical::ParsedEvent, rrule: Option<String>| EventInput {
        calendar_id: id.clone(),
        title: e.title.clone(),
        description: e.description.clone(),
        location: e.location.clone(),
        start: e.start,
        end: e.end,
        all_day: e.all_day,
        timezone: Some(e.timezone.clone()),
        rrule,
        exdates: Some(e.exdates.clone()),
        // Ohne VALARM bleiben vorhandene Erinnerungen erhalten (neue Termine: Kalender-Standard).
        reminders: e.reminders.as_ref().map(|list| {
            let min = if e.all_day { -1439 } else { 0 };
            let mut l: Vec<i32> = list.iter().copied().filter(|m| (min..=reminders::MAX_BEFORE).contains(m)).collect();
            l.sort_unstable();
            l.dedup();
            l.truncate(reminders::MAX_COUNT);
            Some(l)
        }),
    };

    for e in &masters {
        let uid = e.uid.clone().filter(|u| !u.is_empty()).unwrap_or_else(new_uid);
        // Eine ungültige RRULE soll nicht den ganzen Termin verwerfen.
        let data = match EventData::validate(to_input(e, e.rrule.clone())) {
            Ok(d) => d,
            Err(_) => match EventData::validate(to_input(e, None)) {
                Ok(d) => {
                    skipped.push(format!("{}: Wiederholungsregel ignoriert", e.title));
                    d
                }
                Err(err) => {
                    skipped.push(format!("{}: {err}", e.title));
                    continue;
                }
            },
        };
        upsert(&state, &uid, &data).await?;
        imported += 1;
    }

    // Geänderte Einzelvorkommen: als eigenen Termin anlegen und in der Serie ausnehmen.
    for e in &overrides {
        let rid = e.recurrence_id.unwrap();
        let master_uid = e.uid.clone().unwrap_or_default();
        let master = sqlx::query_as::<_, EventRow>("SELECT * FROM events WHERE calendar_id = ? AND uid = ?")
            .bind(&id)
            .bind(&master_uid)
            .fetch_optional(&state.db)
            .await?;
        if let Some(m) = master {
            let mut ev: Event = m.into();
            if ev.rrule.is_some() && !ev.exdates.contains(&rid) {
                ev.exdates.push(rid);
                ev.exdates.sort();
                let json = serde_json::to_string(&ev.exdates.iter().map(|d| time::to_db(*d)).collect::<Vec<_>>())
                    .unwrap();
                sqlx::query("UPDATE events SET exdates = ? WHERE id = ?")
                    .bind(json)
                    .bind(&ev.id)
                    .execute(&state.db)
                    .await?;
            }
        }
        match EventData::validate(to_input(e, None)) {
            Ok(data) => {
                upsert(&state, &format!("{master_uid}#{}", rid.timestamp()), &data).await?;
                imported += 1;
            }
            Err(err) => skipped.push(format!("{}: {err}", e.title)),
        }
    }

    Ok(Json(json!({ "imported": imported, "skipped": skipped })))
}
