//! Automatic birthday calendar: for each contact with a birthday there is a
//! yearly, all-day event in the user's "Geburtstage" calendar.
//!
//! The events are updated on every contact change (REST and CardDAV) and are
//! read-only. Deleted contacts take their event with them via `ON DELETE CASCADE`.

use chrono::{Datelike, Duration, NaiveDate};
use sqlx::{SqliteConnection, SqlitePool};

use crate::{
    error::AppResult,
    models::EventRow,
    time,
    vcard::{self},
};

pub const KIND: &str = "birthdays";
const NAME: &str = "Geburtstage";
const COLOR: &str = "#D81B60";
/// Year for birthdays without a year (a leap year, so Feb 29 is valid too)
const NO_YEAR: i32 = 2000;

/// Returns the ID of the birthday calendar and creates it if needed.
pub async fn ensure_calendar(conn: &mut SqliteConnection, user_id: &str) -> AppResult<String> {
    let now = time::to_db(time::now());
    sqlx::query(
        "INSERT INTO calendars (id, user_id, name, color, description, visible, kind, created_at, updated_at)
         VALUES (?, ?, ?, ?, 'Wird automatisch aus den Kontakten erstellt', 1, ?, ?, ?)
         ON CONFLICT DO NOTHING",
    )
    .bind(uuid::Uuid::new_v4().to_string())
    .bind(user_id)
    .bind(NAME)
    .bind(COLOR)
    .bind(KIND)
    .bind(&now)
    .bind(&now)
    .execute(&mut *conn)
    .await?;
    let (id,): (String,) = sqlx::query_as("SELECT id FROM calendars WHERE user_id = ? AND kind = ?")
        .bind(user_id)
        .bind(KIND)
        .fetch_one(&mut *conn)
        .await?;
    Ok(id)
}

/// Birthday from `YYYY-MM-DD` or `--MM-DD`: (date of the first event, birth year if known)
pub fn parse(birthday: &str) -> Option<(NaiveDate, Option<i32>)> {
    let b = birthday.trim();
    if let Some(md) = b.strip_prefix("--") {
        let date = NaiveDate::parse_from_str(&format!("{NO_YEAR}-{md}"), "%Y-%m-%d").ok()?;
        return Some((date, None));
    }
    let date = NaiveDate::parse_from_str(b, "%Y-%m-%d").ok()?;
    Some((date, Some(date.year())))
}

/// Yearly recurrence; people born on Feb 29 celebrate on Feb 28 in non-leap years.
pub fn rrule_for(date: NaiveDate) -> &'static str {
    if date.month() == 2 && date.day() == 29 {
        "FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=-1"
    } else {
        "FREQ=YEARLY"
    }
}

pub fn title_for(name: &str) -> String {
    format!("🎂 {name}")
}

/// Title of a single occurrence, with age if known: "🎂 Erika Mustermann (62)"
pub fn occurrence_title(title: &str, birth_year: Option<i64>, occurrence_year: i32) -> String {
    match birth_year.map(|y| i64::from(occurrence_year) - y) {
        Some(age) if age > 0 => format!("{title} ({age})"),
        _ => title.to_string(),
    }
}

fn description_for(date: NaiveDate, year: Option<i32>) -> String {
    match year {
        Some(_) => format!("Geboren am {}", date.format("%d.%m.%Y")),
        None => String::new(),
    }
}

fn uid_for(contact_id: &str) -> String {
    format!("birthday-{contact_id}@synchaven")
}

/// Create, update or remove a contact's birthday event.
pub async fn sync_contact(conn: &mut SqliteConnection, contact_id: &str) -> AppResult<()> {
    let row: Option<(String, String, String)> = sqlx::query_as(
        "SELECT c.vcard, c.kind, b.user_id FROM contacts c JOIN addressbooks b ON b.id = c.addressbook_id WHERE c.id = ?",
    )
    .bind(contact_id)
    .fetch_optional(&mut *conn)
    .await?;
    let Some((text, kind, user_id)) = row else {
        return Ok(());
    };
    let wanted = if kind == "group" {
        None
    } else {
        let card = vcard::parse_cards(&text).into_iter().next().unwrap_or_default();
        let data = vcard::extract(&card);
        parse(&data.birthday).map(|b| (b, data.full_name))
    };
    let existing = sqlx::query_as::<_, EventRow>("SELECT * FROM events WHERE contact_id = ?")
        .bind(contact_id)
        .fetch_optional(&mut *conn)
        .await?;

    let Some(((date, year), name)) = wanted else {
        if existing.is_some() {
            sqlx::query("DELETE FROM events WHERE contact_id = ?").bind(contact_id).execute(&mut *conn).await?;
        }
        return Ok(());
    };

    let calendar_id = ensure_calendar(conn, &user_id).await?;
    let title = title_for(&name);
    let description = description_for(date, year);
    let start = time::to_db(time::midnight_utc(date));
    let end = time::to_db(time::midnight_utc(date + Duration::days(1)));
    let rrule = rrule_for(date);
    let birth_year = year.map(i64::from);
    let now = time::to_db(time::now());

    match existing {
        Some(ev)
            if ev.calendar_id == calendar_id
                && ev.title == title
                && ev.description == description
                && ev.start_at == start
                && ev.rrule.as_deref() == Some(rrule)
                && ev.birth_year == birth_year => {}
        Some(ev) => {
            sqlx::query(
                "UPDATE events SET calendar_id = ?, title = ?, description = ?, start_at = ?, end_at = ?,
                 all_day = 1, timezone = 'UTC', rrule = ?, exdates = '[]', birth_year = ?, updated_at = ? WHERE id = ?",
            )
            .bind(&calendar_id)
            .bind(&title)
            .bind(&description)
            .bind(&start)
            .bind(&end)
            .bind(rrule)
            .bind(birth_year)
            .bind(&now)
            .bind(&ev.id)
            .execute(&mut *conn)
            .await?;
        }
        None => {
            sqlx::query(
                "INSERT INTO events (id, calendar_id, uid, title, description, location, start_at, end_at, all_day,
                 timezone, rrule, exdates, contact_id, birth_year, created_at, updated_at)
                 VALUES (?, ?, ?, ?, ?, '', ?, ?, 1, 'UTC', ?, '[]', ?, ?, ?, ?)",
            )
            .bind(uuid::Uuid::new_v4().to_string())
            .bind(&calendar_id)
            .bind(uid_for(contact_id))
            .bind(&title)
            .bind(&description)
            .bind(&start)
            .bind(&end)
            .bind(rrule)
            .bind(contact_id)
            .bind(birth_year)
            .bind(&now)
            .bind(&now)
            .execute(&mut *conn)
            .await?;
        }
    }
    Ok(())
}

/// Syncs all contacts on startup (e.g. for contacts from before the birthday calendar existed).
pub async fn sync_all(db: &SqlitePool) -> AppResult<()> {
    let ids: Vec<(String,)> = sqlx::query_as("SELECT id FROM contacts").fetch_all(db).await?;
    let mut tx = db.begin().await?;
    for (id,) in &ids {
        sync_contact(&mut tx, id).await?;
    }
    tx.commit().await?;
    Ok(())
}

#[cfg(test)]
mod tests {
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
}
