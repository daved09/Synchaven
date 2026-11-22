//! Datenbankzeilen und API-Datentypen.

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::FromRow;

use crate::time::from_db;

// ---------- Benutzer ----------

#[derive(Debug, Clone, FromRow)]
pub struct UserRow {
    pub id: String,
    pub email: String,
    pub name: String,
    pub password_hash: String,
    pub created_at: String,
}

#[derive(Debug, Serialize)]
pub struct User {
    pub id: String,
    pub email: String,
    pub name: String,
    pub created_at: DateTime<Utc>,
}

impl From<UserRow> for User {
    fn from(r: UserRow) -> Self {
        Self { id: r.id, email: r.email, name: r.name, created_at: from_db(&r.created_at) }
    }
}

// ---------- Kalender ----------

#[derive(Debug, Clone, FromRow)]
pub struct CalendarRow {
    pub id: String,
    #[allow(dead_code)]
    pub user_id: String,
    pub name: String,
    pub color: String,
    pub description: String,
    pub visible: bool,
    /// `default` oder `birthdays` (siehe `birthdays.rs`)
    pub kind: String,
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
}

// ---------- Termine ----------

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
    /// Gesetzt bei automatisch erzeugten Geburtstagsterminen
    pub contact_id: Option<String>,
    pub birth_year: Option<i64>,
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
    /// Kontakt, aus dessen Geburtstag der Termin erzeugt wurde
    pub contact_id: Option<String>,
    /// Automatisch erzeugte Termine können nicht bearbeitet werden.
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
}

/// Ein konkretes Vorkommen eines Termins im angefragten Zeitraum.
/// Bei wiederkehrenden Terminen entsteht pro Wiederholung ein Eintrag.
#[derive(Debug, Serialize)]
pub struct Occurrence {
    /// Eindeutige ID des Vorkommens: `<event_id>` bzw. `<event_id>_<startzeit>`
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
}
