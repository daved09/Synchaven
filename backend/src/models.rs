//! Datenbankzeilen und API-Datentypen.

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::FromRow;

use crate::{reminders, time::from_db};

// ---------- Benutzer ----------

#[derive(Debug, Clone, FromRow)]
pub struct UserRow {
    pub id: String,
    pub email: String,
    pub name: String,
    pub password_hash: String,
    pub default_view: String,
    pub created_at: String,
    /// Darf die Verwaltung öffnen (Benutzer, Einstellungen, Datensicherung); zunächst das erste registrierte Konto
    pub is_admin: bool,
    /// Gesperrt: keine Anmeldung, keine Synchronisierung
    pub disabled: bool,
}

#[derive(Debug, Serialize)]
pub struct User {
    pub id: String,
    pub email: String,
    pub name: String,
    /// Standardansicht des Kalenders: `month`, `week`, `day` oder `list`
    pub default_view: String,
    pub created_at: DateTime<Utc>,
    /// Darf die Verwaltung öffnen (zunächst das erste registrierte Konto)
    pub is_admin: bool,
}

impl From<UserRow> for User {
    fn from(r: UserRow) -> Self {
        Self {
            id: r.id,
            email: r.email,
            name: r.name,
            default_view: r.default_view,
            created_at: from_db(&r.created_at),
            is_admin: r.is_admin,
        }
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
    /// Standard-Erinnerungen (Minuten vor Beginn) für Termine mit Uhrzeit
    pub default_reminders: Vec<i32>,
    /// Standard-Erinnerungen für ganztägige Termine (negativ = Uhrzeit am Tag selbst)
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
    /// Fehlt das Feld, bleiben die bisherigen Standard-Erinnerungen erhalten.
    pub default_reminders: Option<Vec<i32>>,
    pub default_all_day_reminders: Option<Vec<i32>>,
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
    /// JSON-Liste (Minuten vor Beginn) oder NULL für die Standard-Erinnerungen des Kalenders
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
    /// Erinnerungen in Minuten vor Beginn; `null` = Standard-Erinnerungen des Kalenders
    pub reminders: Option<Vec<i32>>,
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
    /// Feld fehlt: bisherige Erinnerungen bleiben erhalten; `null`: Kalender-Standard;
    /// Liste: eigene Erinnerungen (leer = keine).
    #[serde(default, deserialize_with = "present")]
    pub reminders: Option<Option<Vec<i32>>>,
}

/// Unterscheidet bei optionalen Feldern zwischen „fehlt“ (`None`) und `null` (`Some(None)`).
fn present<'de, D, T>(d: D) -> Result<Option<Option<T>>, D::Error>
where
    D: serde::Deserializer<'de>,
    T: Deserialize<'de>,
{
    Option::<T>::deserialize(d).map(Some)
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
    /// Erinnerungen des Termins in Minuten vor Beginn; `None` = Standard des Kalenders
    pub reminders: Option<Vec<i32>>,
}

// ---------- Aufgaben ----------

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
    /// Aufgaben dieser Liste im Kalender anzeigen
    pub visible: bool,
    /// Anzahl offener Aufgaben
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
    /// Übergeordnete Aufgabe, wenn dies eine Unteraufgabe ist
    pub parent_id: Option<String>,
    pub title: String,
    pub notes: String,
    /// Fälligkeit; ganztägig als Mitternacht UTC. `null` = ohne Datum
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
    /// Fehlt das Feld, bleibt der Status erhalten (neue Aufgaben: offen).
    pub completed: Option<bool>,
    /// Übergeordnete Aufgabe. Fehlt das Feld, bleibt die Zuordnung erhalten (neue Aufgaben: Hauptaufgabe);
    /// `null` macht eine Unteraufgabe zur Hauptaufgabe.
    #[serde(default, deserialize_with = "present")]
    pub parent_id: Option<Option<String>>,
}

fn default_true() -> bool {
    true
}
