//! Erinnerungen an Termine: Validierung, Berechnung fälliger Erinnerungen und der Hintergrunddienst,
//! der sie per Web Push an die Browser der Benutzer schickt.
//!
//! Eine Erinnerung ist eine Minutenzahl vor Beginn. Bei ganztägigen Terminen bezieht sie sich auf
//! Mitternacht in der Zeitzone des Empfängers; negative Werte bedeuten eine Uhrzeit am Tag selbst
//! (-540 = 09:00), wie `TRIGGER:PT9H` in iCalendar.

use std::{collections::HashMap, time::Duration as StdDuration};

use chrono::{DateTime, Datelike, Duration, Utc};
use chrono_tz::Tz;
use serde_json::json;

use crate::{
    birthdays,
    models::{CalendarRow, Event, EventRow},
    push::{Outcome, Subscription},
    recurrence, time, AppState,
};

pub const MAX_COUNT: usize = 5;
/// Höchstens vier Wochen im Voraus
pub const MAX_BEFORE: i32 = 4 * 7 * 24 * 60;
/// Ganztägig: spätestens 23:59 am Tag selbst
const MIN_ALL_DAY: i32 = -(24 * 60 - 1);

/// Voreinstellung neuer Kalender: 15 Minuten vorher
pub const DEFAULT_TIMED: &[i32] = &[15];
/// Voreinstellung neuer Kalender für ganztägige Termine: am Tag selbst um 09:00
pub const DEFAULT_ALL_DAY: &[i32] = &[-540];

/// Prüfintervall des Hintergrunddienstes
const INTERVAL: StdDuration = StdDuration::from_secs(30);
/// Nach einem Neustart werden verpasste Erinnerungen höchstens so weit rückwirkend gesendet.
const MAX_CATCH_UP: Duration = Duration::minutes(15);
const CHECKPOINT_KEY: &str = "reminders_checked_at";

pub fn from_json(s: &str) -> Vec<i32> {
    serde_json::from_str(s).unwrap_or_default()
}

pub fn to_json(list: &[i32]) -> String {
    serde_json::to_string(list).unwrap()
}

/// Sortiert, entfernt Doppelte und prüft die Grenzen.
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

/// Erinnerungen eines Termins – eigene oder die Standard-Erinnerungen seines Kalenders.
pub fn effective(ev: &Event, cal: &CalendarRow) -> Vec<i32> {
    match &ev.reminders {
        Some(list) => list.clone(),
        None if ev.all_day => from_json(&cal.default_all_day_reminders),
        None => from_json(&cal.default_reminders),
    }
}

/// Eine fällige Erinnerung an ein Vorkommen.
#[derive(Debug, PartialEq)]
pub struct Due {
    pub event_id: String,
    pub title: String,
    pub location: String,
    pub start: DateTime<Utc>,
    pub end: DateTime<Utc>,
    pub all_day: bool,
    pub minutes: i32,
    /// Zeitpunkt der Erinnerung
    pub at: DateTime<Utc>,
}

/// Alle Erinnerungen des Termins, deren Zeitpunkt in (from, to] liegt.
/// `tz` bestimmt, wann ein ganztägiger Termin beginnt.
pub fn due(ev: &Event, birth_year: Option<i64>, reminders: &[i32], tz: Tz, from: DateTime<Utc>, to: DateTime<Utc>) -> Vec<Due> {
    let duration = ev.end - ev.start;
    let mut out = Vec::new();
    for &m in reminders {
        let before = Duration::minutes(m.into());
        // Ganztägige Termine beginnen je nach Zeitzone bis zu 14 Stunden früher oder später.
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

/// Nutzlast der Push-Nachricht; der Service Worker formatiert Zeit und Text selbst.
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

/// Startet den Hintergrunddienst.
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
                // Beim nächsten Durchlauf wird derselbe Zeitraum erneut geprüft.
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
        // Einzeltermine nur im möglichen Zeitfenster, Serien immer
        let rows: Vec<EventRow> = sqlx::query_as(
            "SELECT e.* FROM events e JOIN calendars c ON c.id = e.calendar_id WHERE c.user_id = ?
             AND (e.rrule IS NOT NULL OR (e.start_at >= ? AND e.start_at <= ?))",
        )
        .bind(user_id)
        .bind(time::to_db(from - Duration::days(2)))
        .bind(time::to_db(to + Duration::minutes(MAX_BEFORE.into()) + Duration::days(2)))
        .fetch_all(&state.db)
        .await?;

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
        // Fenster endet genau auf dem Zeitpunkt (inklusiv)
        let d = due(&ev, None, &[10], chrono_tz::UTC, at - Duration::seconds(30), at);
        assert_eq!(d.len(), 1);
        assert_eq!(d[0].at, at);
        // Das folgende Fenster enthält ihn nicht mehr
        assert!(due(&ev, None, &[10], chrono_tz::UTC, at, at + Duration::seconds(30)).is_empty());
    }

    #[test]
    fn recurring_and_all_day_in_local_time() {
        // Täglich 08:00 UTC; Erinnerung 1 Tag vorher für das Vorkommen am 26.11.
        let start = Utc.with_ymd_and_hms(2026, 11, 20, 8, 0, 0).unwrap();
        let ev = event(start, start + Duration::hours(1), false, Some("FREQ=DAILY"));
        let at = Utc.with_ymd_and_hms(2026, 11, 25, 8, 0, 0).unwrap();
        let d = due(&ev, None, &[1440], chrono_tz::UTC, at - Duration::minutes(1), at);
        assert_eq!(d.len(), 1);
        assert_eq!(d[0].start, Utc.with_ymd_and_hms(2026, 11, 26, 8, 0, 0).unwrap());

        // Ganztägig am 24.12., Erinnerung am Vortag 18:00 Berliner Zeit (= 17:00 UTC)
        let day = time::midnight_utc(NaiveDate::from_ymd_opt(2026, 12, 24).unwrap());
        let ev = event(day, day + Duration::days(1), true, None);
        let berlin = chrono_tz::Europe::Berlin;
        let at = Utc.with_ymd_and_hms(2026, 12, 23, 17, 0, 0).unwrap();
        let d = due(&ev, None, &[360], berlin, at - Duration::seconds(30), at);
        assert_eq!(d.len(), 1);
        // Am Tag selbst um 09:00 (= 08:00 UTC)
        let at = Utc.with_ymd_and_hms(2026, 12, 24, 8, 0, 0).unwrap();
        assert_eq!(due(&ev, None, &[-540], berlin, at - Duration::seconds(30), at).len(), 1);
        assert!(due(&ev, None, &[-540], chrono_tz::UTC, at - Duration::seconds(30), at).is_empty());
    }
}
