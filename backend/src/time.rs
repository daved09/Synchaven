//! Hilfsfunktionen für Zeitstempel und Zeitzonen.

use chrono::{DateTime, Duration, NaiveDate, NaiveDateTime, TimeZone, Utc};
use chrono_tz::Tz;

/// Speicherformat für Zeitstempel in der Datenbank (lexikografisch sortierbar).
pub fn to_db(dt: DateTime<Utc>) -> String {
    dt.format("%Y-%m-%dT%H:%M:%SZ").to_string()
}

pub fn from_db(s: &str) -> DateTime<Utc> {
    DateTime::parse_from_rfc3339(s)
        .map(|d| d.with_timezone(&Utc))
        .unwrap_or_else(|_| Utc.timestamp_opt(0, 0).unwrap())
}

pub fn now() -> DateTime<Utc> {
    // Sekundengenau, damit Werte nach dem Speichern identisch bleiben.
    Utc.timestamp_opt(Utc::now().timestamp(), 0).unwrap()
}

pub fn parse_tz(name: &str) -> Option<Tz> {
    name.trim().trim_matches('"').parse::<Tz>().ok()
}

/// Mitternacht (UTC) des Datums – Darstellung ganztägiger Termine.
pub fn midnight_utc(date: NaiveDate) -> DateTime<Utc> {
    Utc.from_utc_datetime(&date.and_hms_opt(0, 0, 0).unwrap())
}

/// Lokale Zeit einer Zeitzone nach UTC umrechnen (DST-Lücken werden übersprungen).
pub fn local_to_utc(local: NaiveDateTime, tz: Tz) -> DateTime<Utc> {
    match tz.from_local_datetime(&local) {
        chrono::LocalResult::Single(d) => d.with_timezone(&Utc),
        chrono::LocalResult::Ambiguous(d, _) => d.with_timezone(&Utc),
        chrono::LocalResult::None => tz
            .from_local_datetime(&(local + Duration::hours(1)))
            .earliest()
            .map(|d| d.with_timezone(&Utc))
            .unwrap_or_else(|| Utc.from_utc_datetime(&local)),
    }
}
