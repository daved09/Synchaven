//! Minimaler iCalendar-(RFC 5545)-Export und -Import für VEVENTs.

use chrono::{DateTime, Duration, NaiveDate, NaiveDateTime, Utc};
use chrono_tz::Tz;

use crate::{models::Event, time};

// ---------- Export ----------

fn escape(s: &str) -> String {
    s.replace('\\', "\\\\")
        .replace(';', "\\;")
        .replace(',', "\\,")
        .replace("\r\n", "\\n")
        .replace('\n', "\\n")
        .replace('\r', "")
}

/// Zeilen nach 75 Oktetts umbrechen (RFC 5545, Abschnitt 3.1).
fn fold(line: &str, out: &mut String) {
    let mut len = 0;
    for ch in line.chars() {
        let w = ch.len_utf8();
        if len + w > 75 {
            out.push_str("\r\n ");
            len = 1;
        }
        out.push(ch);
        len += w;
    }
    out.push_str("\r\n");
}

fn fmt_utc(dt: DateTime<Utc>) -> String {
    dt.format("%Y%m%dT%H%M%SZ").to_string()
}

fn dt_prop(name: &str, dt: DateTime<Utc>, ev: &Event) -> String {
    if ev.all_day {
        return format!("{name};VALUE=DATE:{}", dt.format("%Y%m%d"));
    }
    match time::parse_tz(&ev.timezone) {
        Some(tz) if tz != chrono_tz::UTC => {
            format!("{name};TZID={}:{}", tz.name(), dt.with_timezone(&tz).format("%Y%m%dT%H%M%S"))
        }
        _ => format!("{name}:{}", fmt_utc(dt)),
    }
}

/// Bei ganztägigen Terminen muss UNTIL ein reines Datum sein.
fn export_rrule(rule: &str, all_day: bool) -> String {
    if !all_day {
        return rule.to_string();
    }
    rule.split(';')
        .map(|p| match p.split_once('=') {
            Some(("UNTIL", v)) if v.len() >= 8 => format!("UNTIL={}", &v[..8]),
            _ => p.to_string(),
        })
        .collect::<Vec<_>>()
        .join(";")
}

/// TRIGGER einer Erinnerung (Minuten vor Beginn; negativ = nach Beginn)
fn trigger(minutes: i32) -> String {
    match minutes {
        0 => "PT0S".into(),
        m if m > 0 && m % 1440 == 0 => format!("-P{}D", m / 1440),
        m if m > 0 => format!("-PT{m}M"),
        m => format!("PT{}M", -m),
    }
}

/// `reminders` liefert die Erinnerungen eines Termins (eigene oder Kalender-Standard).
pub fn export(calendar_name: &str, events: &[Event], reminders: impl Fn(&Event) -> Vec<i32>) -> String {
    let mut out = String::new();
    for line in [
        "BEGIN:VCALENDAR",
        "VERSION:2.0",
        "PRODID:-//Synchaven//Synchaven//DE",
        "CALSCALE:GREGORIAN",
        "METHOD:PUBLISH",
    ] {
        fold(line, &mut out);
    }
    fold(&format!("X-WR-CALNAME:{}", escape(calendar_name)), &mut out);

    for ev in events {
        fold("BEGIN:VEVENT", &mut out);
        fold(&format!("UID:{}", escape(&ev.uid)), &mut out);
        fold(&format!("DTSTAMP:{}", fmt_utc(ev.updated_at)), &mut out);
        fold(&format!("CREATED:{}", fmt_utc(ev.created_at)), &mut out);
        fold(&format!("LAST-MODIFIED:{}", fmt_utc(ev.updated_at)), &mut out);
        fold(&dt_prop("DTSTART", ev.start, ev), &mut out);
        fold(&dt_prop("DTEND", ev.end, ev), &mut out);
        fold(&format!("SUMMARY:{}", escape(&ev.title)), &mut out);
        if !ev.description.is_empty() {
            fold(&format!("DESCRIPTION:{}", escape(&ev.description)), &mut out);
        }
        if !ev.location.is_empty() {
            fold(&format!("LOCATION:{}", escape(&ev.location)), &mut out);
        }
        if let Some(rule) = ev.rrule.as_deref().filter(|r| !r.is_empty()) {
            fold(&format!("RRULE:{}", export_rrule(rule, ev.all_day)), &mut out);
            for ex in &ev.exdates {
                fold(&dt_prop("EXDATE", *ex, ev), &mut out);
            }
        }
        for m in reminders(ev) {
            fold("BEGIN:VALARM", &mut out);
            fold("ACTION:DISPLAY", &mut out);
            fold(&format!("DESCRIPTION:{}", escape(&ev.title)), &mut out);
            fold(&format!("TRIGGER:{}", trigger(m)), &mut out);
            fold("END:VALARM", &mut out);
        }
        fold("END:VEVENT", &mut out);
    }
    fold("END:VCALENDAR", &mut out);
    out
}

// ---------- Import ----------

#[derive(Debug, Clone)]
pub struct ParsedEvent {
    pub uid: Option<String>,
    pub title: String,
    pub description: String,
    pub location: String,
    pub start: DateTime<Utc>,
    pub end: DateTime<Utc>,
    pub all_day: bool,
    pub timezone: String,
    pub rrule: Option<String>,
    pub exdates: Vec<DateTime<Utc>>,
    /// Gesetzt bei geänderten Einzelvorkommen einer Serie.
    pub recurrence_id: Option<DateTime<Utc>>,
    /// Erinnerungen aus VALARM (Minuten vor Beginn); `None`, wenn der Termin keine enthält
    pub reminders: Option<Vec<i32>>,
}

struct Prop {
    name: String,
    params: Vec<(String, String)>,
    value: String,
}

impl Prop {
    fn param(&self, key: &str) -> Option<&str> {
        self.params.iter().find(|(k, _)| k == key).map(|(_, v)| v.as_str())
    }
}

fn unescape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut chars = s.chars();
    while let Some(c) = chars.next() {
        if c == '\\' {
            match chars.next() {
                Some('n') | Some('N') => out.push('\n'),
                Some(other) => out.push(other),
                None => {}
            }
        } else {
            out.push(c);
        }
    }
    out
}

fn unfold(text: &str) -> Vec<String> {
    let mut lines: Vec<String> = Vec::new();
    for raw in text.split('\n') {
        let line = raw.strip_suffix('\r').unwrap_or(raw);
        if (line.starts_with(' ') || line.starts_with('\t')) && !lines.is_empty() {
            lines.last_mut().unwrap().push_str(&line[1..]);
        } else if !line.is_empty() {
            lines.push(line.to_string());
        }
    }
    lines
}

/// Teilt an `sep`, ignoriert dabei Trennzeichen innerhalb von Anführungszeichen.
fn split_unquoted(s: &str, sep: char) -> Vec<&str> {
    let mut parts = Vec::new();
    let mut in_quotes = false;
    let mut last = 0;
    for (i, c) in s.char_indices() {
        if c == '"' {
            in_quotes = !in_quotes;
        } else if c == sep && !in_quotes {
            parts.push(&s[last..i]);
            last = i + c.len_utf8();
        }
    }
    parts.push(&s[last..]);
    parts
}

fn parse_prop(line: &str) -> Option<Prop> {
    let mut in_quotes = false;
    let colon = line.char_indices().find_map(|(i, c)| {
        if c == '"' {
            in_quotes = !in_quotes;
        }
        (c == ':' && !in_quotes).then_some(i)
    })?;
    let (head, value) = (&line[..colon], &line[colon + 1..]);
    let mut segs = split_unquoted(head, ';').into_iter();
    let name = segs.next()?.trim().to_uppercase();
    let params = segs
        .filter_map(|p| p.split_once('='))
        .map(|(k, v)| (k.trim().to_uppercase(), v.trim().trim_matches('"').to_string()))
        .collect();
    Some(Prop { name, params, value: value.to_string() })
}

/// Häufige Windows-Zeitzonennamen (Outlook/Exchange) auf IANA abbilden.
fn resolve_tzid(tzid: &str) -> Option<Tz> {
    let tzid = tzid.trim().trim_matches('"');
    if let Some(tz) = time::parse_tz(tzid) {
        return Some(tz);
    }
    // z. B. "/mozilla.org/20050126_1/Europe/Berlin"
    let segs: Vec<&str> = tzid.split('/').filter(|s| !s.is_empty()).collect();
    for n in [3, 2] {
        if segs.len() >= n {
            if let Some(tz) = time::parse_tz(&segs[segs.len() - n..].join("/")) {
                return Some(tz);
            }
        }
    }
    let iana = match tzid {
        "W. Europe Standard Time" => "Europe/Berlin",
        "Central Europe Standard Time" => "Europe/Budapest",
        "Central European Standard Time" => "Europe/Warsaw",
        "Romance Standard Time" => "Europe/Paris",
        "GMT Standard Time" => "Europe/London",
        "E. Europe Standard Time" => "Europe/Bucharest",
        "FLE Standard Time" => "Europe/Helsinki",
        "Eastern Standard Time" => "America/New_York",
        "Central Standard Time" => "America/Chicago",
        "Mountain Standard Time" => "America/Denver",
        "Pacific Standard Time" => "America/Los_Angeles",
        "UTC" | "Coordinated Universal Time" | "GMT" => "UTC",
        _ => return None,
    };
    time::parse_tz(iana)
}

/// Ergebnis: (Zeitpunkt in UTC, ganztägig, Zeitzone des Wertes)
fn parse_dt_value(value: &str, prop: &Prop, default_tz: Tz) -> Option<(DateTime<Utc>, bool, Tz)> {
    let value = value.trim();
    let is_date = prop.param("VALUE").is_some_and(|v| v.eq_ignore_ascii_case("DATE")) || value.len() == 8;
    if is_date {
        let d = NaiveDate::parse_from_str(&value[..8.min(value.len())], "%Y%m%d").ok()?;
        return Some((time::midnight_utc(d), true, default_tz));
    }
    if let Some(v) = value.strip_suffix('Z') {
        let n = NaiveDateTime::parse_from_str(v, "%Y%m%dT%H%M%S").ok()?;
        return Some((n.and_utc(), false, chrono_tz::UTC));
    }
    let n = NaiveDateTime::parse_from_str(value, "%Y%m%dT%H%M%S").ok()?;
    let tz = prop.param("TZID").and_then(resolve_tzid).unwrap_or(default_tz);
    Some((time::local_to_utc(n, tz), false, tz))
}

/// Relativer TRIGGER eines VALARM in Minuten vor Beginn (`-PT15M` → 15, `PT9H` → -540).
/// Absolute Zeitpunkte und Erinnerungen relativ zum Ende werden nicht übernommen.
fn parse_trigger(prop: &Prop) -> Option<i32> {
    if prop.param("VALUE").is_some_and(|v| v.eq_ignore_ascii_case("DATE-TIME"))
        || prop.param("RELATED").is_some_and(|v| v.eq_ignore_ascii_case("END"))
    {
        return None;
    }
    let v = prop.value.trim();
    let minutes = i32::try_from(parse_duration(v)?.num_minutes()).ok()?;
    Some(if v.starts_with('-') { minutes } else { -minutes })
}

/// ISO-8601-Dauer wie `P1D`, `PT1H30M`, `P2W`.
fn parse_duration(s: &str) -> Option<Duration> {
    let s = s.trim().trim_start_matches(['+', '-']);
    let s = s.strip_prefix('P')?;
    let (date, time) = s.split_once('T').unwrap_or((s, ""));
    let mut total = Duration::zero();
    let mut num = String::new();
    for c in date.chars() {
        if c.is_ascii_digit() {
            num.push(c);
        } else {
            let n: i64 = num.parse().ok()?;
            num.clear();
            total += match c {
                'W' => Duration::weeks(n),
                'D' => Duration::days(n),
                _ => return None,
            };
        }
    }
    for c in time.chars() {
        if c.is_ascii_digit() {
            num.push(c);
        } else {
            let n: i64 = num.parse().ok()?;
            num.clear();
            total += match c {
                'H' => Duration::hours(n),
                'M' => Duration::minutes(n),
                'S' => Duration::seconds(n),
                _ => return None,
            };
        }
    }
    Some(total)
}

#[derive(Default)]
struct Builder {
    uid: Option<String>,
    title: String,
    description: String,
    location: String,
    start: Option<(DateTime<Utc>, bool, Tz)>,
    end: Option<(DateTime<Utc>, bool, Tz)>,
    duration: Option<Duration>,
    rrule: Option<String>,
    exdates: Vec<DateTime<Utc>>,
    recurrence_id: Option<DateTime<Utc>>,
    cancelled: bool,
    /// `Some`, sobald ein VALARM vorkommt
    reminders: Option<Vec<i32>>,
    /// TRIGGER des gerade gelesenen VALARM
    alarm: Option<Option<i32>>,
}

impl Builder {
    fn finish(self) -> Option<ParsedEvent> {
        if self.cancelled {
            return None;
        }
        let (start, all_day, tz) = self.start?;
        let end = match (self.end, self.duration) {
            (Some((e, _, _)), _) if e >= start => e,
            (_, Some(d)) => start + d,
            _ if all_day => start + Duration::days(1),
            _ => start,
        };
        let end = if all_day && end <= start { start + Duration::days(1) } else { end };
        Some(ParsedEvent {
            uid: self.uid,
            title: if self.title.trim().is_empty() { "(Ohne Titel)".into() } else { self.title },
            description: self.description,
            location: self.location,
            start,
            end,
            all_day,
            timezone: tz.name().to_string(),
            rrule: self.rrule,
            exdates: self.exdates,
            recurrence_id: self.recurrence_id,
            reminders: self.reminders,
        })
    }
}

pub fn parse(text: &str, default_tz: Tz) -> Result<Vec<ParsedEvent>, String> {
    let lines = unfold(text);
    if !lines.iter().any(|l| l.trim().eq_ignore_ascii_case("BEGIN:VCALENDAR")) {
        return Err("Keine gültige iCalendar-Datei (BEGIN:VCALENDAR fehlt)".into());
    }

    let mut events = Vec::new();
    let mut current: Option<Builder> = None;
    let mut nested = 0usize; // z. B. VALARM innerhalb eines VEVENT

    for line in &lines {
        let Some(prop) = parse_prop(line) else { continue };
        let value_upper = prop.value.trim().to_uppercase();
        match (prop.name.as_str(), current.is_some()) {
            ("BEGIN", false) if value_upper == "VEVENT" => {
                current = Some(Builder::default());
                nested = 0;
            }
            ("BEGIN", true) => {
                nested += 1;
                if nested == 1 && value_upper == "VALARM" {
                    current.as_mut().unwrap().alarm = Some(None);
                }
            }
            ("END", true) if nested > 0 => {
                nested -= 1;
                let b = current.as_mut().unwrap();
                if nested == 0 {
                    if let Some(alarm) = b.alarm.take() {
                        let list = b.reminders.get_or_insert_with(Vec::new);
                        list.extend(alarm);
                    }
                }
            }
            ("TRIGGER", true) if nested == 1 => {
                let b = current.as_mut().unwrap();
                if let Some(alarm) = b.alarm.as_mut() {
                    *alarm = parse_trigger(&prop);
                }
            }
            ("END", true) if value_upper == "VEVENT" => {
                if let Some(ev) = current.take().and_then(Builder::finish) {
                    events.push(ev);
                }
            }
            (_, true) if nested == 0 => {
                let b = current.as_mut().unwrap();
                match prop.name.as_str() {
                    "UID" => b.uid = Some(prop.value.trim().to_string()),
                    "SUMMARY" => b.title = unescape(&prop.value),
                    "DESCRIPTION" => b.description = unescape(&prop.value),
                    "LOCATION" => b.location = unescape(&prop.value),
                    "DTSTART" => b.start = parse_dt_value(&prop.value, &prop, default_tz),
                    "DTEND" => b.end = parse_dt_value(&prop.value, &prop, default_tz),
                    "DURATION" => b.duration = parse_duration(&prop.value),
                    "RRULE" => b.rrule = Some(prop.value.trim().to_string()),
                    "EXDATE" => {
                        for v in prop.value.split(',') {
                            if let Some((dt, _, _)) = parse_dt_value(v, &prop, default_tz) {
                                b.exdates.push(dt);
                            }
                        }
                    }
                    "RECURRENCE-ID" => {
                        b.recurrence_id = parse_dt_value(&prop.value, &prop, default_tz).map(|(d, _, _)| d)
                    }
                    "STATUS" => b.cancelled = value_upper == "CANCELLED",
                    _ => {}
                }
            }
            _ => {}
        }
    }
    Ok(events)
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::TimeZone;

    #[test]
    fn parses_typical_file() {
        let ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:abc\r\nDTSTART;TZID=Europe/Berlin:20261122T100000\r\nDTEND;TZID=Europe/Berlin:20261122T113000\r\nSUMMARY:Team\\, Meeting\r\nDESCRIPTION:Zeile 1\\nZeile\r\n  2\r\nRRULE:FREQ=WEEKLY;COUNT=3\r\nEXDATE;TZID=Europe/Berlin:20261129T100000\r\nBEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:Alarm\r\nEND:VALARM\r\nEND:VEVENT\r\nBEGIN:VEVENT\r\nDTSTART;VALUE=DATE:20261224\r\nSUMMARY:Heiligabend\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
        let evs = parse(ics, chrono_tz::UTC).unwrap();
        assert_eq!(evs.len(), 2);
        let e = &evs[0];
        assert_eq!(e.title, "Team, Meeting");
        assert_eq!(e.description, "Zeile 1\nZeile 2");
        assert_eq!(e.start, Utc.with_ymd_and_hms(2026, 11, 22, 9, 0, 0).unwrap());
        assert_eq!(e.end - e.start, Duration::minutes(90));
        assert_eq!(e.timezone, "Europe/Berlin");
        assert_eq!(e.exdates, vec![Utc.with_ymd_and_hms(2026, 11, 29, 9, 0, 0).unwrap()]);
        assert_eq!(e.reminders, Some(vec![]), "VALARM ohne TRIGGER");
        let h = &evs[1];
        assert!(h.all_day);
        assert_eq!(h.reminders, None);
        assert_eq!(h.end - h.start, Duration::days(1));
    }

    #[test]
    fn export_roundtrip() {
        let start = Utc.with_ymd_and_hms(2026, 11, 22, 9, 0, 0).unwrap();
        let ev = Event {
            id: "1".into(),
            calendar_id: "c".into(),
            uid: "uid-1".into(),
            title: "Lange; Beschreibung, mit Sonderzeichen äöü ".repeat(3),
            description: "a\nb".into(),
            location: String::new(),
            start,
            end: start + Duration::hours(1),
            all_day: false,
            timezone: "Europe/Berlin".into(),
            rrule: Some("FREQ=DAILY;COUNT=2".into()),
            exdates: vec![],
            reminders: None,
            contact_id: None,
            read_only: false,
            created_at: start,
            updated_at: start,
        };
        let ics = export("Test", &[ev.clone()], |_| vec![-540, 0, 15, 2880]);
        assert!(ics.lines().all(|l| l.len() <= 76));
        let back = parse(&ics, chrono_tz::UTC).unwrap();
        assert_eq!(back[0].title, ev.title);
        assert_eq!(back[0].start, ev.start);
        assert_eq!(back[0].description, "a\nb");
        assert_eq!(back[0].rrule.as_deref(), Some("FREQ=DAILY;COUNT=2"));
        assert_eq!(back[0].reminders, Some(vec![-540, 0, 15, 2880]));
    }
}
