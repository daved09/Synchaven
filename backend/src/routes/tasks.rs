//! Aufgabenlisten und Aufgaben. Aufgaben mit Fälligkeit zeigen die Clients im Kalender an.

use std::collections::HashMap;

use axum::{
    extract::{Path, Query, State},
    http::StatusCode,
    Json,
};
use serde::Deserialize;
use serde_json::{json, Value};
use sqlx::SqliteConnection;

use super::calendars::parse_color;
use crate::{
    auth::AuthUser,
    error::{AppError, AppResult},
    models::{Task, TaskInput, TaskList, TaskListInput, TaskListRow, TaskRow},
    time, AppState,
};

pub const DEFAULT_LIST_NAME: &str = "Meine Aufgaben";
pub const DEFAULT_LIST_COLOR: &str = "#1A73E8";

/// Legt die Standardliste an, falls der Benutzer noch keine Aufgabenliste hat.
pub async fn ensure_list(conn: &mut SqliteConnection, user_id: &str) -> AppResult<()> {
    let now = time::to_db(time::now());
    sqlx::query(
        "INSERT INTO task_lists (id, user_id, name, color, visible, created_at, updated_at)
         SELECT ?, ?, ?, ?, 1, ?, ? WHERE NOT EXISTS (SELECT 1 FROM task_lists WHERE user_id = ?)",
    )
    .bind(uuid::Uuid::new_v4().to_string())
    .bind(user_id)
    .bind(DEFAULT_LIST_NAME)
    .bind(DEFAULT_LIST_COLOR)
    .bind(&now)
    .bind(&now)
    .bind(user_id)
    .execute(conn)
    .await?;
    Ok(())
}

async fn owned_list(state: &AppState, user_id: &str, id: &str) -> AppResult<TaskListRow> {
    sqlx::query_as::<_, TaskListRow>("SELECT * FROM task_lists WHERE id = ? AND user_id = ?")
        .bind(id)
        .bind(user_id)
        .fetch_optional(&state.db)
        .await?
        .ok_or(AppError::NotFound)
}

async fn open_counts(state: &AppState, user_id: &str) -> AppResult<HashMap<String, i64>> {
    let rows: Vec<(String, i64)> = sqlx::query_as(
        "SELECT t.list_id, COUNT(*) FROM tasks t JOIN task_lists l ON l.id = t.list_id
         WHERE l.user_id = ? AND t.completed_at IS NULL GROUP BY t.list_id",
    )
    .bind(user_id)
    .fetch_all(&state.db)
    .await?;
    Ok(rows.into_iter().collect())
}

async fn list_response(state: &AppState, user_id: &str, id: &str) -> AppResult<TaskList> {
    let row = owned_list(state, user_id, id).await?;
    let count = open_counts(state, user_id).await?.get(id).copied().unwrap_or(0);
    Ok(TaskList::from_row(row, count))
}

fn validate_list(input: &TaskListInput) -> AppResult<(String, Option<String>)> {
    let name = input.name.trim();
    if name.is_empty() || name.chars().count() > 100 {
        return Err(AppError::BadRequest("Der Name muss 1–100 Zeichen lang sein".into()));
    }
    Ok((name.to_string(), parse_color(input.color.as_deref())?))
}

pub async fn list_lists(State(state): State<AppState>, auth: AuthUser) -> AppResult<Json<Vec<TaskList>>> {
    ensure_list(&mut *state.db.acquire().await?, &auth.id).await?;
    let rows = sqlx::query_as::<_, TaskListRow>("SELECT * FROM task_lists WHERE user_id = ? ORDER BY created_at, name")
        .bind(&auth.id)
        .fetch_all(&state.db)
        .await?;
    let counts = open_counts(&state, &auth.id).await?;
    Ok(Json(
        rows.into_iter()
            .map(|r| {
                let n = counts.get(&r.id).copied().unwrap_or(0);
                TaskList::from_row(r, n)
            })
            .collect(),
    ))
}

pub async fn create_list(
    State(state): State<AppState>,
    auth: AuthUser,
    Json(input): Json<TaskListInput>,
) -> AppResult<(StatusCode, Json<TaskList>)> {
    let (name, color) = validate_list(&input)?;
    let id = uuid::Uuid::new_v4().to_string();
    let now = time::to_db(time::now());
    sqlx::query(
        "INSERT INTO task_lists (id, user_id, name, color, visible, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
    )
    .bind(&id)
    .bind(&auth.id)
    .bind(&name)
    .bind(color.unwrap_or_else(|| DEFAULT_LIST_COLOR.into()))
    .bind(input.visible.unwrap_or(true))
    .bind(&now)
    .bind(&now)
    .execute(&state.db)
    .await?;
    Ok((StatusCode::CREATED, Json(list_response(&state, &auth.id, &id).await?)))
}

pub async fn update_list(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(id): Path<String>,
    Json(input): Json<TaskListInput>,
) -> AppResult<Json<TaskList>> {
    let existing = owned_list(&state, &auth.id, &id).await?;
    let (name, color) = validate_list(&input)?;
    sqlx::query("UPDATE task_lists SET name = ?, color = ?, visible = ?, updated_at = ? WHERE id = ?")
        .bind(&name)
        .bind(color.unwrap_or(existing.color))
        .bind(input.visible.unwrap_or(existing.visible))
        .bind(time::to_db(time::now()))
        .bind(&id)
        .execute(&state.db)
        .await?;
    Ok(Json(list_response(&state, &auth.id, &id).await?))
}

pub async fn delete_list(State(state): State<AppState>, auth: AuthUser, Path(id): Path<String>) -> AppResult<StatusCode> {
    owned_list(&state, &auth.id, &id).await?;
    let (n,): (i64,) = sqlx::query_as("SELECT COUNT(*) FROM task_lists WHERE user_id = ?")
        .bind(&auth.id)
        .fetch_one(&state.db)
        .await?;
    if n <= 1 {
        return Err(AppError::BadRequest("Die letzte Aufgabenliste kann nicht gelöscht werden".into()));
    }
    // Aufgaben werden per ON DELETE CASCADE mitgelöscht.
    sqlx::query("DELETE FROM task_lists WHERE id = ?").bind(&id).execute(&state.db).await?;
    Ok(StatusCode::NO_CONTENT)
}

/// Löscht alle erledigten Aufgaben einer Liste.
pub async fn clear_completed(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(id): Path<String>,
) -> AppResult<Json<Value>> {
    owned_list(&state, &auth.id, &id).await?;
    let res = sqlx::query("DELETE FROM tasks WHERE list_id = ? AND completed_at IS NOT NULL")
        .bind(&id)
        .execute(&state.db)
        .await?;
    Ok(Json(json!({ "deleted": res.rows_affected() })))
}

// ---------- Aufgaben ----------

/// Validierte, speicherfertige Aufgabe.
struct TaskData {
    list_id: String,
    title: String,
    notes: String,
    due: Option<String>,
    all_day: bool,
    completed: Option<bool>,
}

fn validate_task(input: TaskInput) -> AppResult<TaskData> {
    let title = input.title.trim().to_string();
    if title.is_empty() || title.chars().count() > 500 {
        return Err(AppError::BadRequest("Der Titel muss 1–500 Zeichen lang sein".into()));
    }
    // Ohne Datum gibt es keine Uhrzeit
    let all_day = input.all_day || input.due.is_none();
    let due = input.due.map(|d| if all_day { time::midnight_utc(d.date_naive()) } else { d });
    Ok(TaskData {
        list_id: input.list_id,
        title,
        notes: input.notes.trim_end().to_string(),
        due: due.map(time::to_db),
        all_day,
        completed: input.completed,
    })
}

async fn owned_task(state: &AppState, user_id: &str, id: &str) -> AppResult<TaskRow> {
    sqlx::query_as::<_, TaskRow>(
        "SELECT t.* FROM tasks t JOIN task_lists l ON l.id = t.list_id WHERE t.id = ? AND l.user_id = ?",
    )
    .bind(id)
    .bind(user_id)
    .fetch_optional(&state.db)
    .await?
    .ok_or(AppError::NotFound)
}

#[derive(Deserialize)]
pub struct TaskQuery {
    /// Nur Aufgaben dieser Liste (optional)
    list_id: Option<String>,
}

/// Alle Aufgaben des Benutzers: offene vor erledigten, dann nach Fälligkeit (ohne Datum zuletzt).
pub async fn list(
    State(state): State<AppState>,
    auth: AuthUser,
    Query(q): Query<TaskQuery>,
) -> AppResult<Json<Vec<Task>>> {
    let rows: Vec<TaskRow> = sqlx::query_as(
        "SELECT t.* FROM tasks t JOIN task_lists l ON l.id = t.list_id
         WHERE l.user_id = ? AND (? IS NULL OR t.list_id = ?)
         ORDER BY t.completed_at IS NOT NULL, t.due_at IS NULL, t.due_at, t.created_at, t.id",
    )
    .bind(&auth.id)
    .bind(&q.list_id)
    .bind(&q.list_id)
    .fetch_all(&state.db)
    .await?;
    Ok(Json(rows.into_iter().map(Into::into).collect()))
}

pub async fn get(State(state): State<AppState>, auth: AuthUser, Path(id): Path<String>) -> AppResult<Json<Task>> {
    Ok(Json(owned_task(&state, &auth.id, &id).await?.into()))
}

pub async fn create(
    State(state): State<AppState>,
    auth: AuthUser,
    Json(input): Json<TaskInput>,
) -> AppResult<(StatusCode, Json<Task>)> {
    let data = validate_task(input)?;
    owned_list(&state, &auth.id, &data.list_id).await?;
    let id = uuid::Uuid::new_v4().to_string();
    let now = time::to_db(time::now());
    sqlx::query(
        "INSERT INTO tasks (id, list_id, title, notes, due_at, all_day, completed_at, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
    )
    .bind(&id)
    .bind(&data.list_id)
    .bind(&data.title)
    .bind(&data.notes)
    .bind(&data.due)
    .bind(data.all_day)
    .bind(data.completed.unwrap_or(false).then(|| now.clone()))
    .bind(&now)
    .bind(&now)
    .execute(&state.db)
    .await?;
    Ok((StatusCode::CREATED, Json(owned_task(&state, &auth.id, &id).await?.into())))
}

/// Neuer Wert für `completed_at`: Erledigungszeitpunkt bleibt erhalten, solange die Aufgabe erledigt bleibt.
fn completed_at(existing: &TaskRow, completed: Option<bool>) -> Option<String> {
    match completed {
        None => existing.completed_at.clone(),
        Some(false) => None,
        Some(true) => existing.completed_at.clone().or_else(|| Some(time::to_db(time::now()))),
    }
}

pub async fn update(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(id): Path<String>,
    Json(input): Json<TaskInput>,
) -> AppResult<Json<Task>> {
    let existing = owned_task(&state, &auth.id, &id).await?;
    let data = validate_task(input)?;
    owned_list(&state, &auth.id, &data.list_id).await?;
    sqlx::query(
        "UPDATE tasks SET list_id = ?, title = ?, notes = ?, due_at = ?, all_day = ?, completed_at = ?, updated_at = ?
         WHERE id = ?",
    )
    .bind(&data.list_id)
    .bind(&data.title)
    .bind(&data.notes)
    .bind(&data.due)
    .bind(data.all_day)
    .bind(completed_at(&existing, data.completed))
    .bind(time::to_db(time::now()))
    .bind(&id)
    .execute(&state.db)
    .await?;
    Ok(Json(owned_task(&state, &auth.id, &id).await?.into()))
}

#[derive(Deserialize)]
pub struct CompletedInput {
    completed: bool,
}

/// Hakt eine Aufgabe ab oder öffnet sie wieder.
pub async fn set_completed(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(id): Path<String>,
    Json(input): Json<CompletedInput>,
) -> AppResult<Json<Task>> {
    let existing = owned_task(&state, &auth.id, &id).await?;
    sqlx::query("UPDATE tasks SET completed_at = ?, updated_at = ? WHERE id = ?")
        .bind(completed_at(&existing, Some(input.completed)))
        .bind(time::to_db(time::now()))
        .bind(&id)
        .execute(&state.db)
        .await?;
    Ok(Json(owned_task(&state, &auth.id, &id).await?.into()))
}

pub async fn delete(State(state): State<AppState>, auth: AuthUser, Path(id): Path<String>) -> AppResult<StatusCode> {
    owned_task(&state, &auth.id, &id).await?;
    sqlx::query("DELETE FROM tasks WHERE id = ?").bind(&id).execute(&state.db).await?;
    Ok(StatusCode::NO_CONTENT)
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::TimeZone;

    fn input(due: Option<chrono::DateTime<chrono::Utc>>, all_day: bool) -> TaskInput {
        TaskInput { list_id: "l".into(), title: "  Einkaufen ".into(), notes: "Milch\n".into(), due, all_day, completed: None }
    }

    #[test]
    fn all_day_due_is_midnight_utc() {
        let due = chrono::Utc.with_ymd_and_hms(2026, 11, 24, 15, 30, 0).unwrap();
        let d = validate_task(input(Some(due), true)).unwrap();
        assert_eq!(d.due.as_deref(), Some("2026-11-24T00:00:00Z"));
        assert_eq!(d.title, "Einkaufen");
        assert_eq!(d.notes, "Milch");
    }

    #[test]
    fn timed_due_keeps_time_and_no_due_is_all_day() {
        let due = chrono::Utc.with_ymd_and_hms(2026, 11, 24, 15, 30, 0).unwrap();
        let d = validate_task(input(Some(due), false)).unwrap();
        assert_eq!(d.due.as_deref(), Some("2026-11-24T15:30:00Z"));
        assert!(!d.all_day);
        let d = validate_task(input(None, false)).unwrap();
        assert!(d.due.is_none() && d.all_day);
    }

    #[test]
    fn empty_title_is_rejected() {
        let mut i = input(None, true);
        i.title = "   ".into();
        assert!(validate_task(i).is_err());
    }

    #[test]
    fn completed_at_is_kept_while_completed() {
        let row = TaskRow {
            id: "t".into(),
            list_id: "l".into(),
            title: "x".into(),
            notes: String::new(),
            due_at: None,
            all_day: true,
            completed_at: Some("2026-01-01T00:00:00Z".into()),
            created_at: String::new(),
            updated_at: String::new(),
        };
        assert_eq!(completed_at(&row, Some(true)).as_deref(), Some("2026-01-01T00:00:00Z"));
        assert_eq!(completed_at(&row, None).as_deref(), Some("2026-01-01T00:00:00Z"));
        assert_eq!(completed_at(&row, Some(false)), None);
    }
}
