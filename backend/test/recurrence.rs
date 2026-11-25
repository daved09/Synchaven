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
        reminders: None,
        contact_id: None,
        read_only: false,
        created_at: start,
        updated_at: start,
    }
}

#[test]
fn weekly_keeps_local_time_across_dst() {
    // 09:00 Berlin (winter time = 08:00 UTC), weekly into the summer
    let start = Utc.with_ymd_and_hms(2026, 3, 16, 8, 0, 0).unwrap();
    let ev = event(start, start + Duration::hours(1), "Europe/Berlin", "FREQ=WEEKLY;COUNT=4");
    let occ = occurrences(&ev, start, start + Duration::days(30));
    assert_eq!(occ.len(), 4);
    // After the change on March 29, 09:00 Berlin is 07:00 UTC
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
    // Jan 2 (still running until 12:00), Jan 3 excluded, Jan 4
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
