//! Database rows and API data types.

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::FromRow;

use crate::{reminders, time::from_db};

// ---------- Users ----------

#[derive(Debug, Clone, FromRow)]
pub struct UserRow {
    pub id: String,
    pub email: String,
    pub name: String,
    pub password_hash: String,
    pub default_view: String,
    /// Language of stored texts such as default names and birthday events (`de`, `en`)
    pub language: String,
    pub created_at: String,
    /// May open the admin page (users, settings, backup); initially the first registered account
    pub is_admin: bool,
    /// Disabled: no sign-in, no sync
    pub disabled: bool,
}

#[derive(Debug, Serialize)]
pub struct User {
    pub id: String,
    pub email: String,
    pub name: String,
    /// The calendar's default view: `month`, `week`, `day` or `list`
    pub default_view: String,
    /// Language of the texts the server stores for the account (`de`, `en`)
    pub language: String,
    pub created_at: DateTime<Utc>,
    /// May open the admin page (initially the first registered account)
    pub is_admin: bool,
}

impl From<UserRow> for User {
    fn from(r: UserRow) -> Self {
        Self {
            id: r.id,
            email: r.email,
            name: r.name,
            default_view: r.default_view,
            language: r.language,
            created_at: from_db(&r.created_at),
            is_admin: r.is_admin,
        }
    }
}

// ---------- Calendars ----------

#[derive(Debug, Clone, FromRow)]
pub struct CalendarRow {
    pub id: String,
    #[allow(dead_code)]
    pub user_id: String,
    pub name: String,
    pub color: String,
    pub description: String,
    pub visible: bool,
    /// `default` or `birthdays` (see `birthdays.rs`)
    pub kind: String,
    pub default_reminders: String,
    pub default_all_day_reminders: String,
    pub created_at: String,
    pub updated_at: String,
}

#[derive(Debug, Serialize)]
pub struct Calendar {
    pub id: String,
    pub name: String,
    pub color: String,
    pub description: String,
    pub visible: bool,
    pub kind: String,
    /// Default reminders (minutes before the start) for timed events
    pub default_reminders: Vec<i32>,
    /// Default reminders for all-day events (negative = time on the day itself)
    pub default_all_day_reminders: Vec<i32>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

impl From<CalendarRow> for Calendar {
    fn from(r: CalendarRow) -> Self {
        Self {
            id: r.id,
            name: r.name,
            color: r.color,
            description: r.description,
            visible: r.visible,
            kind: r.kind,
            default_reminders: reminders::from_json(&r.default_reminders),
            default_all_day_reminders: reminders::from_json(&r.default_all_day_reminders),
            created_at: from_db(&r.created_at),
            updated_at: from_db(&r.updated_at),
        }
    }
}

#[derive(Debug, Deserialize)]
pub struct CalendarInput {
    pub name: String,
    pub color: Option<String>,
    #[serde(default)]
    pub description: String,
    pub visible: Option<bool>,
    /// If the field is missing, the existing default reminders are kept.
    pub default_reminders: Option<Vec<i32>>,
    pub default_all_day_reminders: Option<Vec<i32>>,
}

// ---------- Events ----------

#[derive(Debug, Clone, FromRow)]
pub struct EventRow {
    pub id: String,
    pub calendar_id: String,
    pub uid: String,
    pub title: String,
    pub description: String,
    pub location: String,
    pub start_at: String,
    pub end_at: String,
    pub all_day: bool,
    pub timezone: String,
    pub rrule: Option<String>,
    pub exdates: String,
    /// Set for automatically generated birthday events
    pub contact_id: Option<String>,
    pub birth_year: Option<i64>,
    /// JSON list (minutes before the start) or NULL for the calendar's default reminders
    pub reminders: Option<String>,
    pub created_at: String,
    pub updated_at: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct Event {
    pub id: String,
    pub calendar_id: String,
    pub uid: String,
    pub title: String,
    pub description: String,
    pub location: String,
    pub start: DateTime<Utc>,
    pub end: DateTime<Utc>,
    pub all_day: bool,
    pub timezone: String,
    pub rrule: Option<String>,
    pub exdates: Vec<DateTime<Utc>>,
    /// Reminders in minutes before the start; `null` = the calendar's default reminders
    pub reminders: Option<Vec<i32>>,
    /// Contact whose birthday the event was generated from
    pub contact_id: Option<String>,
    /// Automatically generated events cannot be edited.
    pub read_only: bool,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

impl From<EventRow> for Event {
    fn from(r: EventRow) -> Self {
        let exdates: Vec<String> = serde_json::from_str(&r.exdates).unwrap_or_default();
        Self {
            id: r.id,
            calendar_id: r.calendar_id,
            uid: r.uid,
            title: r.title,
            description: r.description,
            location: r.location,
            start: from_db(&r.start_at),
            end: from_db(&r.end_at),
            all_day: r.all_day,
            timezone: r.timezone,
            rrule: r.rrule,
            exdates: exdates.iter().map(|s| from_db(s)).collect(),
            reminders: r.reminders.as_deref().map(reminders::from_json),
            read_only: r.contact_id.is_some(),
            contact_id: r.contact_id,
            created_at: from_db(&r.created_at),
            updated_at: from_db(&r.updated_at),
        }
    }
}

#[derive(Debug, Deserialize)]
pub struct EventInput {
    pub calendar_id: String,
    pub title: String,
    #[serde(default)]
    pub description: String,
    #[serde(default)]
    pub location: String,
    pub start: DateTime<Utc>,
    pub end: DateTime<Utc>,
    #[serde(default)]
    pub all_day: bool,
    pub timezone: Option<String>,
    pub rrule: Option<String>,
    pub exdates: Option<Vec<DateTime<Utc>>>,
    /// Field missing: existing reminders are kept; `null`: calendar default;
    /// list: own reminders (empty = none).
    #[serde(default, deserialize_with = "present")]
    pub reminders: Option<Option<Vec<i32>>>,
}

/// Distinguishes between "missing" (`None`) and `null` (`Some(None)`) for optional fields.
fn present<'de, D, T>(d: D) -> Result<Option<Option<T>>, D::Error>
where
    D: serde::Deserializer<'de>,
    T: Deserialize<'de>,
{
    Option::<T>::deserialize(d).map(Some)
}

/// A concrete occurrence of an event in the requested range.
/// Recurring events produce one entry per recurrence.
#[derive(Debug, Serialize)]
pub struct Occurrence {
    /// Unique ID of the occurrence: `<event_id>` or `<event_id>_<start time>`
    pub id: String,
    pub event_id: String,
    pub calendar_id: String,
    pub title: String,
    pub description: String,
    pub location: String,
    pub start: DateTime<Utc>,
    pub end: DateTime<Utc>,
    pub all_day: bool,
    pub timezone: String,
    pub recurring: bool,
    pub rrule: Option<String>,
    pub contact_id: Option<String>,
    pub read_only: bool,
    /// The event's reminders in minutes before the start; `None` = calendar default
    pub reminders: Option<Vec<i32>>,
}

// ---------- Tasks ----------

#[derive(Debug, Clone, FromRow)]
pub struct TaskListRow {
    pub id: String,
    #[allow(dead_code)]
    pub user_id: String,
    pub name: String,
    pub color: String,
    pub visible: bool,
    pub created_at: String,
    pub updated_at: String,
}

#[derive(Debug, Serialize)]
pub struct TaskList {
    pub id: String,
    pub name: String,
    pub color: String,
    /// Show this list's tasks in the calendar
    pub visible: bool,
    /// Number of open tasks
    pub open_count: i64,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

impl TaskList {
    pub fn from_row(r: TaskListRow, open_count: i64) -> Self {
        Self {
            id: r.id,
            name: r.name,
            color: r.color,
            visible: r.visible,
            open_count,
            created_at: from_db(&r.created_at),
            updated_at: from_db(&r.updated_at),
        }
    }
}

#[derive(Debug, Deserialize)]
pub struct TaskListInput {
    pub name: String,
    pub color: Option<String>,
    pub visible: Option<bool>,
}

#[derive(Debug, Clone, FromRow)]
pub struct TaskRow {
    pub id: String,
    pub list_id: String,
    pub parent_id: Option<String>,
    pub title: String,
    pub notes: String,
    pub due_at: Option<String>,
    pub all_day: bool,
    pub completed_at: Option<String>,
    pub created_at: String,
    pub updated_at: String,
}

#[derive(Debug, Serialize)]
pub struct Task {
    pub id: String,
    pub list_id: String,
    /// Parent task if this is a subtask
    pub parent_id: Option<String>,
    pub title: String,
    pub notes: String,
    /// Due date; all-day as midnight UTC. `null` = without date
    pub due: Option<DateTime<Utc>>,
    pub all_day: bool,
    pub completed: bool,
    pub completed_at: Option<DateTime<Utc>>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

impl From<TaskRow> for Task {
    fn from(r: TaskRow) -> Self {
        Self {
            id: r.id,
            list_id: r.list_id,
            parent_id: r.parent_id,
            title: r.title,
            notes: r.notes,
            due: r.due_at.as_deref().map(from_db),
            all_day: r.all_day,
            completed: r.completed_at.is_some(),
            completed_at: r.completed_at.as_deref().map(from_db),
            created_at: from_db(&r.created_at),
            updated_at: from_db(&r.updated_at),
        }
    }
}

#[derive(Debug, Deserialize)]
pub struct TaskInput {
    pub list_id: String,
    pub title: String,
    #[serde(default)]
    pub notes: String,
    pub due: Option<DateTime<Utc>>,
    #[serde(default = "default_true")]
    pub all_day: bool,
    /// If the field is missing, the status is kept (new tasks: open).
    pub completed: Option<bool>,
    /// Parent task. If the field is missing, the assignment is kept (new tasks: top-level task);
    /// `null` turns a subtask into a top-level task.
    #[serde(default, deserialize_with = "present")]
    pub parent_id: Option<Option<String>>,
}

fn default_true() -> bool {
    true
}
