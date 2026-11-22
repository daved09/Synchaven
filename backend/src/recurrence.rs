//! Wiederkehrende Termine (RFC 5545 RRULE) validieren und expandieren.

use chrono::{DateTime, Duration, NaiveDate, NaiveDateTime, Utc};
use rrule::{RRule, RRuleSet, Tz as RTz, Unvalidated};

use crate::{models::Event, time};

/// Maximale Anzahl Vorkommen pro Termin und Abfrage.
const MAX_OCCURRENCES: u16 = 3000;

fn dtstart(start: DateTime<Utc>, tz: chrono_tz::Tz, all_day: bool) -> DateTime<RTz> {
    if all_day {
        // Ganztägige Termine werden in UTC (Mitternacht) geführt.
        start.with_timezone(&RTz::UTC)
    } else {
        start.with_timezone(&RTz::Tz(tz))
    }
}

/// Normalisiert eine RRULE (ohne `RRULE:`-Präfix, Großschreibung, UNTIL immer in UTC)
/// und prüft sie auf Gültigkeit.
pub fn normalize(rule: &str, start: DateTime<Utc>, tz: chrono_tz::Tz, all_day: bool) -> Result<String, String> {
    let mut s = rule.trim().to_string();
    if s.to_uppercase().starts_with("RRULE:") {
        s = s[6..].to_string();
    }
    let s = s.to_uppercase();

    let parts: Vec<String> = s
        .split(';')
        .map(str::trim)
        .filter(|p| !p.is_empty())
        .map(|part| match part.split_once('=') {
            Some(("UNTIL", v)) => format!("UNTIL={}", normalize_until(v, tz, all_day)),
            _ => part.to_string(),
        })
        .collect();
    if parts.is_empty() {
        return Err("Leere Wiederholungsregel".into());
    }
    let normalized = parts.join(";");

    build(&normalized, start, tz, all_day)?;
    Ok(normalized)
}

/// UNTIL muss laut RFC 5545 bei zeitzonenbehafteten Startzeiten in UTC angegeben werden.
fn normalize_until(v: &str, tz: chrono_tz::Tz, all_day: bool) -> String {
    let v = v.trim();
    if v.ends_with('Z') {
        return v.to_string();
    }
    let utc = if let Ok(d) = NaiveDate::parse_from_str(v, "%Y%m%d") {
        // Ein reines Datum schließt den ganzen Tag ein.
        let local = d.and_hms_opt(23, 59, 59).unwrap();
        if all_day {
            time::midnight_utc(d) + Duration::seconds(86399)
        } else {
            time::local_to_utc(local, tz)
        }
    } else if let Ok(local) = NaiveDateTime::parse_from_str(v, "%Y%m%dT%H%M%S") {
        if all_day {
            time::midnight_utc(local.date()) + Duration::seconds(86399)
        } else {
            time::local_to_utc(local, tz)
        }
    } else {
        return v.to_string(); // wird beim Validieren abgelehnt
    };
    utc.format("%Y%m%dT%H%M%SZ").to_string()
}

fn build(rule: &str, start: DateTime<Utc>, tz: chrono_tz::Tz, all_day: bool) -> Result<RRuleSet, String> {
    let parsed: RRule<Unvalidated> = rule.parse().map_err(|e| format!("Ungültige Wiederholungsregel: {e}"))?;
    parsed
        .build(dtstart(start, tz, all_day))
        .map_err(|e| format!("Ungültige Wiederholungsregel: {e}"))
}

/// Liefert die Startzeitpunkte aller Vorkommen, die den Zeitraum [from, to) berühren.
pub fn occurrences(ev: &Event, from: DateTime<Utc>, to: DateTime<Utc>) -> Vec<DateTime<Utc>> {
    let duration = ev.end - ev.start;
    let overlaps = |s: DateTime<Utc>| s < to && (s + duration > from || (duration.is_zero() && s >= from));

    let Some(rule) = ev.rrule.as_deref().filter(|r| !r.trim().is_empty()) else {
        return if overlaps(ev.start) { vec![ev.start] } else { vec![] };
    };
    let tz = time::parse_tz(&ev.timezone).unwrap_or(chrono_tz::UTC);
    let set = match build(rule, ev.start, tz, ev.all_day) {
        Ok(set) => set,
        Err(e) => {
            tracing::warn!("Termin {}: {e}", ev.id);
            return if overlaps(ev.start) { vec![ev.start] } else { vec![] };
        }
    };

    let after = (from - duration).with_timezone(&RTz::UTC);
    let before = to.with_timezone(&RTz::UTC);
    set.after(after)
        .before(before)
        .all(MAX_OCCURRENCES)
        .dates
        .into_iter()
        .map(|d| d.with_timezone(&Utc))
        .filter(|d| overlaps(*d) && !ev.exdates.contains(d))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::TimeZone;

    fn event(start: DateTime<Utc>, end: DateTime<Utc>, tz: &str, rrule: &str) -> Event {
        Event {
            id: "e".into(),
            calendar_id: "c".into(),
            uid: "u".into(),
            title: "t".into(),
            description: String::new(),
            location: String::new(),
            start,
            end,
            all_day: false,
            timezone: tz.into(),
            rrule: Some(rrule.into()),
            exdates: vec![],
            created_at: start,
            updated_at: start,
        }
    }

    #[test]
    fn weekly_keeps_local_time_across_dst() {
        // 09:00 Berlin (Winterzeit = 08:00 UTC), wöchentlich bis in den Sommer
        let start = Utc.with_ymd_and_hms(2026, 3, 16, 8, 0, 0).unwrap();
        let ev = event(start, start + Duration::hours(1), "Europe/Berlin", "FREQ=WEEKLY;COUNT=4");
        let occ = occurrences(&ev, start, start + Duration::days(30));
        assert_eq!(occ.len(), 4);
        // Nach der Umstellung am 29.03. liegt 09:00 Berlin bei 07:00 UTC
        assert_eq!(occ[2], Utc.with_ymd_and_hms(2026, 3, 30, 7, 0, 0).unwrap());
    }

    #[test]
    fn exdates_and_range() {
        let start = Utc.with_ymd_and_hms(2026, 1, 1, 10, 0, 0).unwrap();
        let mut ev = event(start, start + Duration::hours(2), "UTC", "FREQ=DAILY");
        ev.exdates = vec![Utc.with_ymd_and_hms(2026, 1, 3, 10, 0, 0).unwrap()];
        let from = Utc.with_ymd_and_hms(2026, 1, 2, 11, 0, 0).unwrap();
        let to = Utc.with_ymd_and_hms(2026, 1, 5, 0, 0, 0).unwrap();
        let occ = occurrences(&ev, from, to);
        // 02.01. (läuft noch bis 12:00), 03.01. ausgenommen, 04.01.
        assert_eq!(occ, vec![
            Utc.with_ymd_and_hms(2026, 1, 2, 10, 0, 0).unwrap(),
            Utc.with_ymd_and_hms(2026, 1, 4, 10, 0, 0).unwrap(),
        ]);
    }

    #[test]
    fn until_date_is_normalized() {
        let start = Utc.with_ymd_and_hms(2026, 1, 1, 8, 0, 0).unwrap();
        let r = normalize("rrule:freq=daily;until=20260105", start, chrono_tz::Europe::Berlin, false).unwrap();
        assert_eq!(r, "FREQ=DAILY;UNTIL=20260105T225959Z");
        assert!(normalize("FREQ=SOMETIMES", start, chrono_tz::UTC, false).is_err());
    }
}
