use super::*;

#[test]
fn parses_birthdays() {
    assert_eq!(parse("1964-08-12"), Some((NaiveDate::from_ymd_opt(1964, 8, 12).unwrap(), Some(1964))));
    assert_eq!(parse("--03-04"), Some((NaiveDate::from_ymd_opt(NO_YEAR, 3, 4).unwrap(), None)));
    assert_eq!(parse("--02-29").map(|(d, _)| rrule_for(d)), Some("FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=-1"));
    assert_eq!(parse(""), None);
    assert_eq!(parse("1964-02-30"), None);
}

#[test]
fn age_in_title() {
    assert_eq!(occurrence_title("🎂 A", Some(1964), 2026), "🎂 A (62)");
    assert_eq!(occurrence_title("🎂 A", Some(2026), 2026), "🎂 A");
    assert_eq!(occurrence_title("🎂 A", None, 2026), "🎂 A");
}

#[test]
fn leap_day_falls_back_to_feb_28() {
    use crate::models::Event;
    use chrono::TimeZone;
    let start = time::midnight_utc(NaiveDate::from_ymd_opt(1988, 2, 29).unwrap());
    let ev = Event {
        id: "e".into(),
        calendar_id: "c".into(),
        uid: "u".into(),
        title: "t".into(),
        description: String::new(),
        location: String::new(),
        start,
        end: start + Duration::days(1),
        all_day: true,
        timezone: "UTC".into(),
        rrule: Some(rrule_for(start.date_naive()).into()),
        exdates: vec![],
        reminders: None,
        contact_id: Some("k".into()),
        read_only: true,
        created_at: start,
        updated_at: start,
    };
    let from = chrono::Utc.with_ymd_and_hms(2027, 1, 1, 0, 0, 0).unwrap();
    let to = chrono::Utc.with_ymd_and_hms(2029, 1, 1, 0, 0, 0).unwrap();
    let occ = crate::recurrence::occurrences(&ev, from, to);
    assert_eq!(occ, vec![
        chrono::Utc.with_ymd_and_hms(2027, 2, 28, 0, 0, 0).unwrap(),
        chrono::Utc.with_ymd_and_hms(2028, 2, 29, 0, 0, 0).unwrap(),
    ]);
}
