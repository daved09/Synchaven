use super::*;
use chrono::TimeZone;

fn input(due: Option<chrono::DateTime<chrono::Utc>>, all_day: bool) -> TaskInput {
    TaskInput {
        list_id: "l".into(),
        title: "  Einkaufen ".into(),
        notes: "Milch\n".into(),
        due,
        all_day,
        completed: None,
        parent_id: None,
    }
}

#[test]
fn all_day_due_is_midnight_utc() {
    let due = chrono::Utc.with_ymd_and_hms(2026, 11, 24, 15, 30, 0).unwrap();
    let d = validate_task(input(Some(due), true)).unwrap();
    assert_eq!(d.due.as_deref(), Some("2026-11-24T00:00:00Z"));
    assert_eq!(d.title, "Einkaufen");
    assert_eq!(d.notes, "Milch");
}

#[test]
fn timed_due_keeps_time_and_no_due_is_all_day() {
    let due = chrono::Utc.with_ymd_and_hms(2026, 11, 24, 15, 30, 0).unwrap();
    let d = validate_task(input(Some(due), false)).unwrap();
    assert_eq!(d.due.as_deref(), Some("2026-11-24T15:30:00Z"));
    assert!(!d.all_day);
    let d = validate_task(input(None, false)).unwrap();
    assert!(d.due.is_none() && d.all_day);
}

#[test]
fn parent_id_distinguishes_missing_and_null() {
    let parse = |j: &str| serde_json::from_str::<TaskInput>(j).unwrap().parent_id;
    assert_eq!(parse(r#"{"list_id":"l","title":"x"}"#), None);
    assert_eq!(parse(r#"{"list_id":"l","title":"x","parent_id":null}"#), Some(None));
    assert_eq!(parse(r#"{"list_id":"l","title":"x","parent_id":"p"}"#), Some(Some("p".into())));
}

#[test]
fn empty_title_is_rejected() {
    let mut i = input(None, true);
    i.title = "   ".into();
    assert!(validate_task(i).is_err());
}

#[test]
fn completed_at_is_kept_while_completed() {
    let row = TaskRow {
        id: "t".into(),
        list_id: "l".into(),
        parent_id: None,
        title: "x".into(),
        notes: String::new(),
        due_at: None,
        all_day: true,
        completed_at: Some("2026-01-01T00:00:00Z".into()),
        position: 0,
        created_at: String::new(),
        updated_at: String::new(),
    };
    assert_eq!(completed_at(&row, Some(true)).as_deref(), Some("2026-01-01T00:00:00Z"));
    assert_eq!(completed_at(&row, None).as_deref(), Some("2026-01-01T00:00:00Z"));
    assert_eq!(completed_at(&row, Some(false)), None);
}

#[test]
fn reorder_inserts_after_the_given_sibling() {
    let sibs = || vec!["a".to_string(), "b".into(), "c".into()];
    assert_eq!(reorder(sibs(), "x", None).unwrap(), ["x", "a", "b", "c"]);
    assert_eq!(reorder(sibs(), "x", Some("b")).unwrap(), ["a", "b", "x", "c"]);
    assert_eq!(reorder(sibs(), "x", Some("c")).unwrap(), ["a", "b", "c", "x"]);
    assert!(reorder(sibs(), "x", Some("other")).is_err());
    assert_eq!(reorder(vec![], "x", None).unwrap(), ["x"]);
}

#[test]
fn list_sort_by_is_validated() {
    let list = |sort_by: Option<&str>| TaskListInput {
        name: "Einkauf".into(),
        color: None,
        visible: None,
        sort_by: sort_by.map(Into::into),
    };
    assert_eq!(validate_list(&list(None)).unwrap().sort_by, None);
    assert_eq!(validate_list(&list(Some("due"))).unwrap().sort_by.as_deref(), Some("due"));
    assert!(validate_list(&list(Some("title"))).is_err());
}
