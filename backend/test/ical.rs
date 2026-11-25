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
