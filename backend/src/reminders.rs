//! Event reminders: validation, computation of due reminders and the background service
//! that sends them to the users' browsers via Web Push.
//!
//! A reminder is a number of minutes before the start. For all-day events it refers to
//! midnight in the recipient's time zone; negative values mean a time on the day itself
//! (-540 = 09:00), like `TRIGGER:PT9H` in iCalendar.
//!
//! Like in Google Tasks, open tasks with a due date remind once: at the due time, or for all-day
//! tasks on the due date at [`TASK_ALL_DAY_MINUTE`] in the recipient's time zone.

use std::{collections::HashMap, time::Duration as StdDuration};

use chrono::{DateTime, Datelike, Duration, Utc};
use chrono_tz::Tz;
use serde_json::json;

use crate::{
    birthdays,
    models::{CalendarRow, Event, EventRow, Task, TaskRow},
    push::{Outcome, Subscription},
    recurrence, time, AppState,
};

pub const MAX_COUNT: usize = 5;
/// At most four weeks in advance
pub const MAX_BEFORE: i32 = 4 * 7 * 24 * 60;
/// All-day: at the latest 23:59 on the day itself
const MIN_ALL_DAY: i32 = -(24 * 60 - 1);

/// Preset for new calendars: 15 minutes before
pub const DEFAULT_TIMED: &[i32] = &[15];
/// Preset for new calendars for all-day events: on the day itself at 09:00
pub const DEFAULT_ALL_DAY: &[i32] = &[-540];

/// Reminder for all-day tasks: on the due date at 09:00 (minutes after midnight)
pub const TASK_ALL_DAY_MINUTE: i64 = 9 * 60;

/// Check interval of the background service
const INTERVAL: StdDuration = StdDuration::from_secs(30);
/// After a restart, missed reminders are sent retroactively at most this far back.
const MAX_CATCH_UP: Duration = Duration::minutes(15);
const CHECKPOINT_KEY: &str = "reminders_checked_at";

pub fn from_json(s: &str) -> Vec<i32> {
    serde_json::from_str(s).unwrap_or_default()
}

pub fn to_json(list: &[i32]) -> String {
    serde_json::to_string(list).unwrap()
}

/// Sorts, removes duplicates and checks the limits.
pub fn validate(mut list: Vec<i32>, all_day: bool) -> Result<Vec<i32>, String> {
    list.sort_unstable();
    list.dedup();
    if list.len() > MAX_COUNT {
        return Err(format!("Höchstens {MAX_COUNT} Erinnerungen pro Termin"));
    }
    let min = if all_day { MIN_ALL_DAY } else { 0 };
    if list.iter().any(|m| *m < min || *m > MAX_BEFORE) {
        return Err(if all_day {
            "Erinnerungen an ganztägige Termine: höchstens 4 Wochen vorher, spätestens 23:59 am Tag selbst".into()
        } else {
            "Erinnerungen: höchstens 4 Wochen und frühestens zum Beginn des Termins".into()
        });
    }
    Ok(list)
}

/// An event's reminders – its own or the default reminders of its calendar.
pub fn effective(ev: &Event, cal: &CalendarRow) -> Vec<i32> {
    match &ev.reminders {
        Some(list) => list.clone(),
        None if ev.all_day => from_json(&cal.default_all_day_reminders),
        None => from_json(&cal.default_reminders),
    }
}

/// A due reminder for an occurrence.
#[derive(Debug, PartialEq)]
pub struct Due {
    pub event_id: String,
    pub title: String,
    pub location: String,
    pub start: DateTime<Utc>,
    pub end: DateTime<Utc>,
    pub all_day: bool,
    pub minutes: i32,
    /// Time of the reminder
    pub at: DateTime<Utc>,
}

/// All reminders of the event whose time lies in (from, to].
/// `tz` determines when an all-day event begins.
pub fn due(ev: &Event, birth_year: Option<i64>, reminders: &[i32], tz: Tz, from: DateTime<Utc>, to: DateTime<Utc>) -> Vec<Due> {
    let duration = ev.end - ev.start;
    let mut out = Vec::new();
    for &m in reminders {
        let before = Duration::minutes(m.into());
        // Depending on the time zone, all-day events begin up to 14 hours earlier or later.
        let slack = if ev.all_day { Duration::days(1) } else { Duration::zero() };
        let starts = recurrence::occurrences(ev, from + before - slack, to + before + slack + Duration::seconds(1));
        for start in starts {
            let begins = if ev.all_day { time::local_to_utc(start.naive_utc(), tz) } else { start };
            let at = begins - before;
            if at <= from || at > to {
                continue;
            }
            out.push(Due {
                event_id: ev.id.clone(),
                title: if ev.contact_id.is_some() {
                    birthdays::occurrence_title(&ev.title, birth_year, start.year())
                } else {
                    ev.title.clone()
                },
                location: ev.location.clone(),
                start,
                end: start + duration,
                all_day: ev.all_day,
                minutes: m,
                at,
            });
        }
    }
    out
}

/// Payload of the push message; the service worker formats time and text itself.
pub fn payload(d: &Due) -> serde_json::Value {
    json!({
        "type": "reminder",
        "tag": format!("{}_{}_{}", d.event_id, d.start.timestamp(), d.minutes),
        "event_id": d.event_id,
        "title": d.title,
        "location": d.location,
        "start": d.start,
        "end": d.end,
        "all_day": d.all_day,
        "minutes": d.minutes,
    })
}

/// A due reminder for a task.
#[derive(Debug, PartialEq)]
pub struct TaskDue {
    pub task_id: String,
    pub list_id: String,
    pub title: String,
    pub notes: String,
    /// Due date (all-day: midnight UTC of the day)
    pub due: DateTime<Utc>,
    pub all_day: bool,
    /// Time of the reminder
    pub at: DateTime<Utc>,
}

/// Time of the reminder for a task; `None` for completed tasks and tasks without a date.
pub fn task_reminder_at(task: &Task, tz: Tz) -> Option<DateTime<Utc>> {
    if task.completed {
        return None;
    }
    let due = task.due?;
    Some(if task.all_day {
        time::local_to_utc(due.naive_utc() + Duration::minutes(TASK_ALL_DAY_MINUTE), tz)
    } else {
        due
    })
}

/// Reminder for the task if its time lies in (from, to].
pub fn task_due(task: &Task, tz: Tz, from: DateTime<Utc>, to: DateTime<Utc>) -> Option<TaskDue> {
    let at = task_reminder_at(task, tz).filter(|at| *at > from && *at <= to)?;
    Some(TaskDue {
        task_id: task.id.clone(),
        list_id: task.list_id.clone(),
        title: task.title.clone(),
        notes: task.notes.clone(),
        due: task.due?,
        all_day: task.all_day,
        at,
    })
}

/// Payload of the push message for a task.
pub fn task_payload(d: &TaskDue) -> serde_json::Value {
    json!({
        "type": "task",
        "tag": format!("task_{}_{}", d.task_id, d.due.timestamp()),
        "task_id": d.task_id,
        "list_id": d.list_id,
        "title": d.title,
        "notes": d.notes,
        "due": d.due,
        "all_day": d.all_day,
    })
}

/// Starts the background service.
pub fn spawn(state: AppState) {
    tokio::spawn(async move {
        let now = time::now();
        let saved: Option<(String,)> = sqlx::query_as("SELECT value FROM server_state WHERE key = ?")
            .bind(CHECKPOINT_KEY)
            .fetch_optional(&state.db)
            .await
            .ok()
            .flatten();
        let mut last = saved.map(|(v,)| time::from_db(&v)).filter(|t| *t <= now).unwrap_or(now).max(now - MAX_CATCH_UP);

        let mut ticker = tokio::time::interval(INTERVAL);
        ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
        loop {
            ticker.tick().await;
            let now = time::now();
            if now <= last {
                continue;
            }
            match run(&state, last, now).await {
                Ok(()) => {
                    last = now;
                    let _ = sqlx::query(
                        "INSERT INTO server_state (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                    )
                    .bind(CHECKPOINT_KEY)
                    .bind(time::to_db(now))
                    .execute(&state.db)
                    .await;
                }
                // The same range is checked again on the next run.
                Err(e) => tracing::warn!("Erinnerungen konnten nicht geprüft werden: {e:#}"),
            }
        }
    });
}

#[derive(sqlx::FromRow)]
struct SubRow {
    id: String,
    user_id: String,
    endpoint: String,
    p256dh: String,
    auth: String,
    timezone: String,
}

async fn run(state: &AppState, from: DateTime<Utc>, to: DateTime<Utc>) -> anyhow::Result<()> {
    let subs: Vec<SubRow> = sqlx::query_as("SELECT id, user_id, endpoint, p256dh, auth, timezone FROM push_subscriptions")
        .fetch_all(&state.db)
        .await?;
    let mut by_user: HashMap<&str, Vec<&SubRow>> = HashMap::new();
    for s in &subs {
        by_user.entry(&s.user_id).or_default().push(s);
    }

    for (user_id, subs) in by_user {
        let calendars: HashMap<String, CalendarRow> = sqlx::query_as::<_, CalendarRow>("SELECT * FROM calendars WHERE user_id = ?")
            .bind(user_id)
            .fetch_all(&state.db)
            .await?
            .into_iter()
            .map(|c| (c.id.clone(), c))
            .collect();
        // Single events only within the possible time window, series always
        let rows: Vec<EventRow> = sqlx::query_as(
            "SELECT e.* FROM events e JOIN calendars c ON c.id = e.calendar_id WHERE c.user_id = ?
             AND (e.rrule IS NOT NULL OR (e.start_at >= ? AND e.start_at <= ?))",
        )
        .bind(user_id)
        .bind(time::to_db(from - Duration::days(2)))
        .bind(time::to_db(to + Duration::minutes(MAX_BEFORE.into()) + Duration::days(2)))
        .fetch_all(&state.db)
        .await?;

        // Open tasks whose reminder may fall into the time window (all-day: ± 1 day because of the time zone)
        let tasks: Vec<Task> = sqlx::query_as::<_, TaskRow>(
            "SELECT t.* FROM tasks t JOIN task_lists l ON l.id = t.list_id WHERE l.user_id = ?
             AND t.completed_at IS NULL AND t.due_at IS NOT NULL AND t.due_at >= ? AND t.due_at <= ?",
        )
        .bind(user_id)
        .bind(time::to_db(from - Duration::days(2)))
        .bind(time::to_db(to + Duration::days(2)))
        .fetch_all(&state.db)
        .await?
        .into_iter()
        .map(Task::from)
        .collect();

        let mut tzs: Vec<&str> = subs.iter().map(|s| s.timezone.as_str()).collect();
        tzs.sort_unstable();
        tzs.dedup();
        for tz_name in tzs {
            let tz = time::parse_tz(tz_name).unwrap_or(chrono_tz::UTC);
            let mut dues = Vec::new();
            for row in &rows {
                let Some(cal) = calendars.get(&row.calendar_id) else { continue };
                let birth_year = row.birth_year;
                let ev: Event = row.clone().into();
                let list = effective(&ev, cal);
                if !list.is_empty() {
                    dues.extend(due(&ev, birth_year, &list, tz, from, to));
                }
            }
            for d in &dues {
                let body = payload(d).to_string();
                let ttl = (d.end - to).num_seconds().clamp(60, 86_400) as u64;
                for sub in subs.iter().filter(|s| s.timezone == tz_name) {
                    deliver(state, sub, body.as_bytes(), StdDuration::from_secs(ttl)).await;
                }
            }
            for d in tasks.iter().filter_map(|t| task_due(t, tz, from, to)) {
                let body = task_payload(&d).to_string();
                // Deliverable until the end of the due date, at least one hour
                let ttl = 86_400 - (to - d.at).num_seconds().clamp(0, 86_400 - 3_600);
                for sub in subs.iter().filter(|s| s.timezone == tz_name) {
                    deliver(state, sub, body.as_bytes(), StdDuration::from_secs(ttl as u64)).await;
                }
            }
        }
    }
    Ok(())
}

async fn deliver(state: &AppState, sub: &SubRow, body: &[u8], ttl: StdDuration) {
    let target = Subscription { endpoint: &sub.endpoint, p256dh: &sub.p256dh, auth: &sub.auth };
    match state.push.send(&target, body, ttl).await {
        Ok(Outcome::Sent) => {}
        Ok(Outcome::Gone) => {
            tracing::info!("Push-Abonnement {} ist abgelaufen und wird entfernt", sub.id);
            let _ = sqlx::query("DELETE FROM push_subscriptions WHERE id = ?").bind(&sub.id).execute(&state.db).await;
        }
        Err(e) => tracing::warn!("Push an Abonnement {} fehlgeschlagen: {e:#}", sub.id),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::{NaiveDate, TimeZone};

    fn event(start: DateTime<Utc>, end: DateTime<Utc>, all_day: bool, rrule: Option<&str>) -> Event {
        Event {
            id: "e".into(),
            calendar_id: "c".into(),
            uid: "u".into(),
            title: "Termin".into(),
            description: String::new(),
            location: String::new(),
            start,
            end,
            all_day,
            timezone: "Europe/Berlin".into(),
            rrule: rrule.map(Into::into),
            exdates: vec![],
            reminders: None,
            contact_id: None,
            read_only: false,
            created_at: start,
            updated_at: start,
        }
    }

    fn task(due: Option<DateTime<Utc>>, all_day: bool, completed: bool) -> Task {
        let t = Utc.with_ymd_and_hms(2026, 11, 1, 0, 0, 0).unwrap();
        Task {
            id: "t".into(),
            list_id: "l".into(),
            parent_id: None,
            title: "Aufgabe".into(),
            notes: String::new(),
            due,
            all_day,
            completed,
            completed_at: completed.then_some(t),
            created_at: t,
            updated_at: t,
        }
    }

    #[test]
    fn task_reminders() {
        let w = Duration::seconds(30);
        // With a time: exactly at the due time
        let at = Utc.with_ymd_and_hms(2026, 11, 24, 13, 0, 0).unwrap();
        let t = task(Some(at), false, false);
        let d = task_due(&t, chrono_tz::UTC, at - w, at).unwrap();
        assert_eq!(d.at, at);
        assert!(task_due(&t, chrono_tz::UTC, at, at + w).is_none());
        // Completed or without a date: no reminder
        assert!(task_due(&task(Some(at), false, true), chrono_tz::UTC, at - w, at).is_none());
        assert!(task_due(&task(None, true, false), chrono_tz::UTC, at - w, at + w).is_none());

        // All-day on Dec 24: at 09:00 Berlin time (= 08:00 UTC)
        let day = time::midnight_utc(NaiveDate::from_ymd_opt(2026, 12, 24).unwrap());
        let t = task(Some(day), true, false);
        let at = Utc.with_ymd_and_hms(2026, 12, 24, 8, 0, 0).unwrap();
        assert_eq!(task_due(&t, chrono_tz::Europe::Berlin, at - w, at).map(|d| d.at), Some(at));
        assert!(task_due(&t, chrono_tz::UTC, at - w, at).is_none());
        assert_eq!(task_payload(&task_due(&t, chrono_tz::Europe::Berlin, at - w, at).unwrap())["type"], "task");
    }

    #[test]
    fn validation() {
        assert_eq!(validate(vec![30, 10, 10], false), Ok(vec![10, 30]));
        assert!(validate(vec![-5], false).is_err());
        assert_eq!(validate(vec![-540], true), Ok(vec![-540]));
        assert!(validate(vec![MAX_BEFORE + 1], true).is_err());
        assert!(validate(vec![1, 2, 3, 4, 5, 6], false).is_err());
    }

    #[test]
    fn timed_reminder_fires_once() {
        let start = Utc.with_ymd_and_hms(2026, 11, 24, 13, 0, 0).unwrap();
        let ev = event(start, start + Duration::hours(1), false, None);
        let at = start - Duration::minutes(10);
        // Window ends exactly at the point in time (inclusive)
        let d = due(&ev, None, &[10], chrono_tz::UTC, at - Duration::seconds(30), at);
        assert_eq!(d.len(), 1);
        assert_eq!(d[0].at, at);
        // The following window no longer contains it
        assert!(due(&ev, None, &[10], chrono_tz::UTC, at, at + Duration::seconds(30)).is_empty());
    }

    #[test]
    fn recurring_and_all_day_in_local_time() {
        // Daily 08:00 UTC; reminder 1 day before for the occurrence on Nov 26
        let start = Utc.with_ymd_and_hms(2026, 11, 20, 8, 0, 0).unwrap();
        let ev = event(start, start + Duration::hours(1), false, Some("FREQ=DAILY"));
        let at = Utc.with_ymd_and_hms(2026, 11, 25, 8, 0, 0).unwrap();
        let d = due(&ev, None, &[1440], chrono_tz::UTC, at - Duration::minutes(1), at);
        assert_eq!(d.len(), 1);
        assert_eq!(d[0].start, Utc.with_ymd_and_hms(2026, 11, 26, 8, 0, 0).unwrap());

        // All-day on Dec 24, reminder the day before at 18:00 Berlin time (= 17:00 UTC)
        let day = time::midnight_utc(NaiveDate::from_ymd_opt(2026, 12, 24).unwrap());
        let ev = event(day, day + Duration::days(1), true, None);
        let berlin = chrono_tz::Europe::Berlin;
        let at = Utc.with_ymd_and_hms(2026, 12, 23, 17, 0, 0).unwrap();
        let d = due(&ev, None, &[360], berlin, at - Duration::seconds(30), at);
        assert_eq!(d.len(), 1);
        // On the day itself at 09:00 (= 08:00 UTC)
        let at = Utc.with_ymd_and_hms(2026, 12, 24, 8, 0, 0).unwrap();
        assert_eq!(due(&ev, None, &[-540], berlin, at - Duration::seconds(30), at).len(), 1);
        assert!(due(&ev, None, &[-540], chrono_tz::UTC, at - Duration::seconds(30), at).is_empty());
    }
}
