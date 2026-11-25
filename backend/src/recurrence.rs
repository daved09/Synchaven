//! Validate and expand recurring events (RFC 5545 RRULE).

use crate::i18n::tr;
use chrono::{DateTime, Duration, NaiveDate, NaiveDateTime, Utc};
use rrule::{RRule, RRuleSet, Tz as RTz, Unvalidated};

use crate::{models::Event, time};

/// Maximum number of occurrences per event and query.
const MAX_OCCURRENCES: u16 = 3000;

fn dtstart(start: DateTime<Utc>, tz: chrono_tz::Tz, all_day: bool) -> DateTime<RTz> {
    if all_day {
        // All-day events are kept in UTC (midnight).
        start.with_timezone(&RTz::UTC)
    } else {
        start.with_timezone(&RTz::Tz(tz))
    }
}

/// Normalizes an RRULE (without `RRULE:` prefix, upper case, UNTIL always in UTC)
/// and checks it for validity.
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
        return Err(tr!("recurrence.empty"));
    }
    let normalized = parts.join(";");

    build(&normalized, start, tz, all_day)?;
    Ok(normalized)
}

/// According to RFC 5545, UNTIL must be given in UTC for start times with a time zone.
fn normalize_until(v: &str, tz: chrono_tz::Tz, all_day: bool) -> String {
    let v = v.trim();
    if v.ends_with('Z') {
        return v.to_string();
    }
    let utc = if let Ok(d) = NaiveDate::parse_from_str(v, "%Y%m%d") {
        // A plain date includes the whole day.
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
        return v.to_string(); // rejected during validation
    };
    utc.format("%Y%m%dT%H%M%SZ").to_string()
}

fn build(rule: &str, start: DateTime<Utc>, tz: chrono_tz::Tz, all_day: bool) -> Result<RRuleSet, String> {
    let parsed: RRule<Unvalidated> = rule.parse().map_err(|e| tr!("recurrence.invalid", error = e))?;
    parsed
        .build(dtstart(start, tz, all_day))
        .map_err(|e| tr!("recurrence.invalid", error = e))
}

/// Returns the start times of all occurrences that touch the range [from, to).
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
            tracing::warn!("Event {}: {e}", ev.id);
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
#[path = "../test/recurrence.rs"]
mod tests;
