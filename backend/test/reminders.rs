use super::*;
use chrono::{NaiveDate, TimeZone};

fn event(start: DateTime<Utc>, end: DateTime<Utc>, all_day: bool, rrule: Option<&str>) -> Event {
    Event {
        id: "e".into(),
        calendar_id: "c".into(),
        uid: "u".into(),
        title: "Event".into(),
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
        title: "Task".into(),
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
