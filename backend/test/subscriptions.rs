use super::*;

#[test]
fn webcal_becomes_https_and_other_schemes_are_refused() {
    assert_eq!(normalize_url(" webcal://example.org/a.ics ").unwrap().as_str(), "https://example.org/a.ics");
    assert_eq!(normalize_url("WEBCALS://example.org/a.ics").unwrap().as_str(), "https://example.org/a.ics");
    assert_eq!(normalize_url("http://example.org/a.ics").unwrap().as_str(), "http://example.org/a.ics");
    for bad in ["", "example.org/a.ics", "ftp://example.org/a.ics", "file:///etc/passwd", "https://"] {
        assert_eq!(normalize_url(bad).unwrap_err(), FetchError::InvalidUrl, "{bad}");
    }
}

#[test]
fn private_addresses_are_not_public() {
    for ip in [
        "127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.178.1", "169.254.169.254", "0.0.0.0", "100.64.0.1",
        "::1", "::", "fd00::1", "fe80::1", "::ffff:192.168.0.1",
    ] {
        assert!(!is_public(ip.parse().unwrap()), "{ip}");
    }
    for ip in ["142.250.185.78", "1.1.1.1", "2a00:1450:4001:82b::200e"] {
        assert!(is_public(ip.parse().unwrap()), "{ip}");
    }
}

#[test]
fn ip_literals_are_checked_without_resolver() {
    let url = |s: &str| Url::parse(s).unwrap();
    assert!(!literal_allowed(&url("http://127.0.0.1:8080/x.ics"), false));
    assert!(!literal_allowed(&url("http://[::1]/x.ics"), false));
    assert!(literal_allowed(&url("http://127.0.0.1/x.ics"), true));
    assert!(literal_allowed(&url("https://example.org/x.ics"), false));
}

#[tokio::test]
async fn fetching_a_private_address_is_blocked() {
    let fetcher = Fetcher::new(false).unwrap();
    let url = normalize_url("http://127.0.0.1:9/x.ics").unwrap();
    assert_eq!(fetcher.fetch(&url).await.err(), Some(FetchError::Blocked));
    let url = normalize_url("http://localhost:9/x.ics").unwrap();
    assert_eq!(fetcher.fetch(&url).await.err(), Some(FetchError::Blocked));
}

const FEED: &str = "BEGIN:VCALENDAR\r
VERSION:2.0\r
X-WR-CALNAME:Feiertage in Deutschland\r
X-WR-TIMEZONE:Europe/Berlin\r
BEGIN:VEVENT\r
UID:newyear@example.org\r
DTSTART;VALUE=DATE:20270101\r
DTEND;VALUE=DATE:20270102\r
SUMMARY:Neujahr\r
BEGIN:VALARM\r
TRIGGER:-PT15M\r
END:VALARM\r
END:VEVENT\r
BEGIN:VEVENT\r
UID:weekly@example.org\r
DTSTART:20270104T100000\r
DTEND:20270104T110000\r
RRULE:FREQ=WEEKLY;COUNT=5\r
SUMMARY:Weekly\r
END:VEVENT\r
BEGIN:VEVENT\r
UID:weekly@example.org\r
RECURRENCE-ID:20270111T100000\r
DTSTART:20270111T120000\r
DTEND:20270111T130000\r
SUMMARY:Weekly (moved)\r
END:VEVENT\r
BEGIN:VEVENT\r
DTSTART;VALUE=DATE:20270501\r
SUMMARY:Without UID\r
END:VEVENT\r
END:VCALENDAR\r
";

#[test]
fn feed_name_comes_from_the_calendar() {
    assert_eq!(feed_name(&Feed { text: FEED.into() }).as_deref(), Some("Feiertage in Deutschland"));
    assert_eq!(feed_name(&Feed { text: "BEGIN:VCALENDAR\nEND:VCALENDAR".into() }), None);
}

#[test]
fn events_of_a_feed() {
    let feed = Feed { text: FEED.into() };
    let mut events = events_of(&feed, "cal").unwrap();
    events.sort_by(|a, b| a.0.cmp(&b.0));
    let uids: Vec<&str> = events.iter().map(|(u, _)| u.as_str()).collect();
    assert_eq!(uids.len(), 4);
    assert!(uids.contains(&"newyear@example.org"));
    assert!(uids.contains(&"weekly@example.org"));
    let moved = format!("weekly@example.org#{}", chrono::TimeZone::with_ymd_and_hms(&Utc, 2027, 1, 11, 9, 0, 0).unwrap().timestamp());
    assert!(uids.contains(&moved.as_str()), "{uids:?}");

    let get = |uid: &str| &events.iter().find(|(u, _)| u == uid).unwrap().1;
    // Floating times use X-WR-TIMEZONE; the moved occurrence is excluded from the series
    let weekly = get("weekly@example.org");
    assert_eq!(time::to_db(weekly.start), "2027-01-04T09:00:00Z");
    assert_eq!(weekly.exdates.iter().map(|d| time::to_db(*d)).collect::<Vec<_>>(), ["2027-01-11T09:00:00Z"]);
    // VALARMs of the file are ignored: the calendar's defaults apply
    assert_eq!(get("newyear@example.org").reminders, Some(None));

    // Without UID: a stable one from the content, the same on every refresh
    let generated = uids.iter().find(|u| u.ends_with("@subscription")).unwrap().to_string();
    let again = events_of(&feed, "cal").unwrap();
    assert!(again.iter().any(|(u, _)| *u == generated));
}

#[test]
fn a_page_is_not_a_calendar() {
    let feed = Feed { text: "<!doctype html><html></html>".into() };
    assert_eq!(events_of(&feed, "cal").err(), Some(FetchError::NotCalendar));
}
