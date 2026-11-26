//! Subscribed calendars: the server fetches an iCalendar file (e.g. public holidays) from a URL and keeps the
//! calendar's events in line with it – on creation, on request and in the background every few hours.
//!
//! The events are read-only (`events.subscribed`). They keep their IDs across refreshes (matched by UID), so clients
//! that sync (Android calendar) only see what really changed. Reminders of the file (VALARM) are ignored: a
//! subscription starts without default reminders, the user can set some for the calendar.
//!
//! The server fetches the address on behalf of the user. So that it cannot be used to probe the server's own network,
//! addresses that resolve to loopback, private or link-local IPs are refused unless the administrator allows them
//! (`SUBSCRIPTIONS_ALLOW_PRIVATE`, e.g. for a calendar on the home network).

use std::{
    collections::HashMap,
    error::Error as StdError,
    net::{IpAddr, Ipv4Addr, SocketAddr},
    sync::Arc,
    time::Duration as StdDuration,
};

use chrono::{DateTime, Duration, Utc};
use reqwest::{redirect, Url};
use sqlx::SqliteConnection;

use crate::{
    error::AppResult,
    i18n::{tr_in, Lang},
    ical,
    models::{EventInput, EventRow},
    routes::events::EventData,
    time, AppState,
};

pub const KIND: &str = "subscription";
/// Refresh interval after a successful refresh
pub const REFRESH_EVERY: Duration = Duration::hours(6);
/// After a failed refresh, the next attempt comes sooner
const RETRY_AFTER: Duration = Duration::hours(1);
/// How often the background service looks for due subscriptions
const CHECK_EVERY: StdDuration = StdDuration::from_secs(10 * 60);
const MAX_URL_LEN: usize = 2000;
const MAX_BYTES: usize = 10 * 1024 * 1024;
const MAX_EVENTS: usize = 20_000;
const MAX_REDIRECTS: usize = 5;
const TIMEOUT: StdDuration = StdDuration::from_secs(30);

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FetchError {
    InvalidUrl,
    /// The address points into a private network
    Blocked,
    Timeout,
    Unreachable,
    Status(u16),
    TooLarge,
    NotCalendar,
    TooManyEvents,
}

impl FetchError {
    pub fn message(&self, lang: Lang) -> String {
        match self {
            Self::InvalidUrl => tr_in!(lang, "subscription.invalid_url"),
            Self::Blocked => tr_in!(lang, "subscription.blocked"),
            Self::Timeout => tr_in!(lang, "subscription.timeout"),
            Self::Unreachable => tr_in!(lang, "subscription.unreachable"),
            Self::Status(code) => tr_in!(lang, "subscription.http_status", status = code),
            Self::TooLarge => tr_in!(lang, "subscription.too_large", mb = MAX_BYTES / 1024 / 1024),
            Self::NotCalendar => tr_in!(lang, "subscription.not_calendar"),
            Self::TooManyEvents => tr_in!(lang, "subscription.too_many_events", max = MAX_EVENTS),
        }
    }
}

/// Address as entered → address to fetch: `webcal(s)://` becomes `https://`, only `http`/`https` with a host.
pub fn normalize_url(input: &str) -> Result<Url, FetchError> {
    let s = input.trim();
    if s.is_empty() || s.len() > MAX_URL_LEN {
        return Err(FetchError::InvalidUrl);
    }
    let lower = s.to_ascii_lowercase();
    let s = if lower.starts_with("webcal://") {
        format!("https://{}", &s["webcal://".len()..])
    } else if lower.starts_with("webcals://") {
        format!("https://{}", &s["webcals://".len()..])
    } else {
        s.to_string()
    };
    let url = Url::parse(&s).map_err(|_| FetchError::InvalidUrl)?;
    if !matches!(url.scheme(), "http" | "https") || url.host_str().is_none_or(str::is_empty) {
        return Err(FetchError::InvalidUrl);
    }
    Ok(url)
}

/// Whether an address is reachable on the public internet (no loopback, private, link-local … ranges).
pub fn is_public(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => is_public_v4(v4),
        IpAddr::V6(v6) => {
            if let Some(v4) = v6.to_ipv4_mapped() {
                return is_public_v4(v4);
            }
            let seg0 = v6.segments()[0];
            !(v6.is_loopback()
                || v6.is_unspecified()
                || v6.is_multicast()
                || (seg0 & 0xfe00) == 0xfc00 // unique local fc00::/7
                || (seg0 & 0xffc0) == 0xfe80 // link-local fe80::/10
                || (seg0 & 0xffc0) == 0xfec0 // site-local (deprecated) fec0::/10
                || (seg0 == 0x2001 && v6.segments()[1] == 0x0db8)) // documentation
        }
    }
}

fn is_public_v4(ip: Ipv4Addr) -> bool {
    let o = ip.octets();
    !(ip.is_loopback()
        || ip.is_private()
        || ip.is_link_local()
        || ip.is_unspecified()
        || ip.is_broadcast()
        || ip.is_documentation()
        || ip.is_multicast()
        || o[0] == 0
        || (o[0] == 100 && (o[1] & 0xc0) == 64) // shared address space 100.64.0.0/10
        || (o[0] == 192 && o[1] == 0 && o[2] == 0) // IETF protocol assignments
        || (o[0] == 198 && (o[1] & 0xfe) == 18) // benchmarking 198.18.0.0/15
        || o[0] >= 240)
}

/// Marker error of the resolver, recognized again in reqwest's error chain
#[derive(Debug)]
struct BlockedAddress;

impl std::fmt::Display for BlockedAddress {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("address not allowed")
    }
}

impl StdError for BlockedAddress {}

/// DNS resolver that drops non-public addresses (unless allowed), so redirects and DNS tricks cannot reach them either.
struct Resolver {
    allow_private: bool,
}

impl reqwest::dns::Resolve for Resolver {
    fn resolve(&self, name: reqwest::dns::Name) -> reqwest::dns::Resolving {
        let allow_private = self.allow_private;
        Box::pin(async move {
            let addrs: Vec<SocketAddr> = tokio::net::lookup_host((name.as_str(), 0))
                .await?
                .filter(|a| allow_private || is_public(a.ip()))
                .collect();
            if addrs.is_empty() {
                return Err(Box::new(BlockedAddress) as Box<dyn StdError + Send + Sync>);
            }
            Ok(Box::new(addrs.into_iter()) as reqwest::dns::Addrs)
        })
    }
}

/// Host given as an IP address: the resolver is not asked, so it is checked here.
fn literal_allowed(url: &Url, allow_private: bool) -> bool {
    let host = url.host_str().unwrap_or_default();
    match host.trim_start_matches('[').trim_end_matches(']').parse::<IpAddr>() {
        Ok(ip) => allow_private || is_public(ip),
        Err(_) => true,
    }
}

pub struct Fetcher {
    client: reqwest::Client,
    allow_private: bool,
    /// One refresh at a time, so a manual and a background refresh of the same calendar don't collide
    apply_lock: tokio::sync::Mutex<()>,
}

/// Content of a fetched file
pub struct Feed {
    pub text: String,
}

impl Fetcher {
    pub fn new(allow_private: bool) -> anyhow::Result<Self> {
        let client = reqwest::Client::builder()
            .user_agent(concat!("Synchaven/", env!("SYNCHAVEN_VERSION"), " (calendar subscription)"))
            .timeout(TIMEOUT)
            .connect_timeout(StdDuration::from_secs(10))
            .dns_resolver(Arc::new(Resolver { allow_private }))
            .redirect(redirect::Policy::custom(move |attempt| {
                if attempt.previous().len() >= MAX_REDIRECTS {
                    attempt.error("too many redirects")
                } else if !matches!(attempt.url().scheme(), "http" | "https") {
                    attempt.error("unsupported scheme")
                } else if !literal_allowed(attempt.url(), allow_private) {
                    attempt.error(BlockedAddress)
                } else {
                    attempt.follow()
                }
            }))
            .build()?;
        Ok(Self { client, allow_private, apply_lock: tokio::sync::Mutex::new(()) })
    }

    pub async fn fetch(&self, url: &Url) -> Result<Feed, FetchError> {
        if !literal_allowed(url, self.allow_private) {
            return Err(FetchError::Blocked);
        }
        let mut res = self
            .client
            .get(url.clone())
            .header(reqwest::header::ACCEPT, "text/calendar, */*;q=0.5")
            .send()
            .await
            .map_err(classify)?;
        if !res.status().is_success() {
            return Err(FetchError::Status(res.status().as_u16()));
        }
        if res.content_length().is_some_and(|l| l > MAX_BYTES as u64) {
            return Err(FetchError::TooLarge);
        }
        let mut body = Vec::new();
        while let Some(chunk) = res.chunk().await.map_err(classify)? {
            if body.len() + chunk.len() > MAX_BYTES {
                return Err(FetchError::TooLarge);
            }
            body.extend_from_slice(&chunk);
        }
        let text = String::from_utf8_lossy(&body);
        let text = text.strip_prefix('\u{feff}').unwrap_or(&text).to_string();
        Ok(Feed { text })
    }
}

fn classify(e: reqwest::Error) -> FetchError {
    let mut source: Option<&(dyn StdError + 'static)> = Some(&e);
    while let Some(s) = source {
        if s.is::<BlockedAddress>() {
            return FetchError::Blocked;
        }
        source = s.source();
    }
    if e.is_timeout() {
        FetchError::Timeout
    } else {
        FetchError::Unreachable
    }
}

/// Name for a new subscription from the file (`X-WR-CALNAME`), at most 100 characters
pub fn feed_name(feed: &Feed) -> Option<String> {
    ical::calendar_property(&feed.text, "X-WR-CALNAME").map(|n| n.chars().take(100).collect::<String>().trim().to_string())
}

/// The events of a file as (UID, data). Modified single occurrences become events of their own and are excluded from
/// their series, as with the .ics import. Events without UID get a stable one from their content.
pub fn events_of(feed: &Feed, calendar_id: &str) -> Result<Vec<(String, EventData)>, FetchError> {
    let default_tz = ical::calendar_property(&feed.text, "X-WR-TIMEZONE")
        .as_deref()
        .and_then(time::parse_tz)
        .unwrap_or(chrono_tz::UTC);
    let parsed = ical::parse(&feed.text, default_tz).map_err(|_| FetchError::NotCalendar)?;
    if parsed.len() > MAX_EVENTS {
        return Err(FetchError::TooManyEvents);
    }

    let uid_of = |e: &ical::ParsedEvent| {
        e.uid.clone().filter(|u| !u.trim().is_empty()).unwrap_or_else(|| {
            use sha2::{Digest, Sha256};
            let hash = Sha256::digest(format!("{}\n{}\n{}", e.title, time::to_db(e.start), time::to_db(e.end)));
            let hex: String = hash.iter().map(|b| format!("{b:02x}")).collect();
            format!("{hex}@subscription")
        })
    };
    // Occurrences replaced by a modified one, per series
    let mut overridden: HashMap<String, Vec<DateTime<Utc>>> = HashMap::new();
    for e in parsed.iter() {
        if let Some(rid) = e.recurrence_id {
            overridden.entry(uid_of(e)).or_default().push(rid);
        }
    }

    let to_input = |e: &ical::ParsedEvent, rrule: Option<String>, exdates: Vec<DateTime<Utc>>| EventInput {
        calendar_id: calendar_id.to_string(),
        title: e.title.chars().take(500).collect(),
        description: e.description.clone(),
        location: e.location.clone(),
        start: e.start,
        end: e.end,
        all_day: e.all_day,
        timezone: Some(e.timezone.clone()),
        rrule,
        exdates: Some(exdates),
        reminders: Some(None),
    };

    let mut out: HashMap<String, EventData> = HashMap::new();
    for e in &parsed {
        let uid = uid_of(e);
        let (key, data) = match e.recurrence_id {
            None => {
                let mut exdates = e.exdates.clone();
                exdates.extend(overridden.get(&uid).into_iter().flatten().copied());
                // An invalid RRULE should not discard the whole event.
                let data = EventData::validate(to_input(e, e.rrule.clone(), exdates))
                    .or_else(|_| EventData::validate(to_input(e, None, vec![])));
                (uid, data)
            }
            Some(rid) => (format!("{uid}#{}", rid.timestamp()), EventData::validate(to_input(e, None, vec![]))),
        };
        if let Ok(data) = data {
            out.insert(key, data);
        }
    }
    Ok(out.into_iter().collect())
}

/// Replaces the calendar's events with those of the file; unchanged events are not touched.
/// Returns (added or changed, removed).
pub async fn apply(conn: &mut SqliteConnection, calendar_id: &str, events: &[(String, EventData)]) -> AppResult<(usize, usize)> {
    let existing: Vec<EventRow> =
        sqlx::query_as("SELECT * FROM events WHERE calendar_id = ?").bind(calendar_id).fetch_all(&mut *conn).await?;
    let mut by_uid: HashMap<&str, &EventRow> = existing.iter().map(|e| (e.uid.as_str(), e)).collect();
    let now = time::to_db(time::now());
    let mut changed = 0;
    for (uid, d) in events {
        let exdates = d.exdates_json();
        let (start, end) = (time::to_db(d.start), time::to_db(d.end));
        match by_uid.remove(uid.as_str()) {
            Some(ev)
                if ev.title == d.title
                    && ev.description == d.description
                    && ev.location == d.location
                    && ev.start_at == start
                    && ev.end_at == end
                    && ev.all_day == d.all_day
                    && ev.timezone == d.timezone
                    && ev.rrule == d.rrule
                    && ev.exdates == exdates
                    && ev.subscribed => {}
            Some(ev) => {
                sqlx::query(
                    "UPDATE events SET title = ?, description = ?, location = ?, start_at = ?, end_at = ?, all_day = ?,
                     timezone = ?, rrule = ?, exdates = ?, reminders = NULL, subscribed = 1, updated_at = ? WHERE id = ?",
                )
                .bind(&d.title)
                .bind(&d.description)
                .bind(&d.location)
                .bind(&start)
                .bind(&end)
                .bind(d.all_day)
                .bind(&d.timezone)
                .bind(&d.rrule)
                .bind(&exdates)
                .bind(&now)
                .bind(&ev.id)
                .execute(&mut *conn)
                .await?;
                changed += 1;
            }
            None => {
                sqlx::query(
                    "INSERT INTO events (id, calendar_id, uid, title, description, location, start_at, end_at, all_day,
                     timezone, rrule, exdates, subscribed, created_at, updated_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)",
                )
                .bind(uuid::Uuid::new_v4().to_string())
                .bind(calendar_id)
                .bind(uid)
                .bind(&d.title)
                .bind(&d.description)
                .bind(&d.location)
                .bind(&start)
                .bind(&end)
                .bind(d.all_day)
                .bind(&d.timezone)
                .bind(&d.rrule)
                .bind(&exdates)
                .bind(&now)
                .bind(&now)
                .execute(&mut *conn)
                .await?;
                changed += 1;
            }
        }
    }
    let removed = by_uid.len();
    for ev in by_uid.into_values() {
        sqlx::query("DELETE FROM events WHERE id = ?").bind(&ev.id).execute(&mut *conn).await?;
    }
    Ok((changed, removed))
}

/// Fetches a subscription and stores the result (events or error). `Err` only for database errors;
/// a failed fetch is returned as `Ok(Some(error))` and stored in `sync_error`.
pub async fn refresh(state: &AppState, calendar_id: &str, lang: Lang) -> AppResult<Option<FetchError>> {
    let row: Option<(Option<String>,)> =
        sqlx::query_as("SELECT source_url FROM calendars WHERE id = ? AND kind = ?")
            .bind(calendar_id)
            .bind(KIND)
            .fetch_optional(&state.db)
            .await?;
    let Some((Some(source_url),)) = row else { return Ok(None) };

    let result = match normalize_url(&source_url) {
        Ok(url) => match state.subscriptions.fetch(&url).await {
            Ok(feed) => events_of(&feed, calendar_id),
            Err(e) => Err(e),
        },
        Err(e) => Err(e),
    };
    let now = time::to_db(time::now());
    let _guard = state.subscriptions.apply_lock.lock().await;
    let mut tx = state.db.begin().await?;
    let error = match result {
        Ok(events) => {
            let (changed, removed) = apply(&mut tx, calendar_id, &events).await?;
            sqlx::query("UPDATE calendars SET synced_at = ?, sync_attempted_at = ?, sync_error = NULL WHERE id = ?")
                .bind(&now)
                .bind(&now)
                .bind(calendar_id)
                .execute(&mut *tx)
                .await?;
            tracing::info!(
                "Subscribed calendar {calendar_id} refreshed: {} event(s), {changed} added or changed, {removed} removed",
                events.len()
            );
            None
        }
        Err(e) => {
            sqlx::query("UPDATE calendars SET sync_attempted_at = ?, sync_error = ? WHERE id = ?")
                .bind(&now)
                .bind(e.message(lang))
                .bind(calendar_id)
                .execute(&mut *tx)
                .await?;
            tracing::info!("Subscribed calendar {calendar_id} could not be refreshed: {e:?}");
            Some(e)
        }
    };
    tx.commit().await?;
    Ok(error)
}

/// Starts the background service that refreshes due subscriptions.
pub fn spawn(state: AppState) {
    tokio::spawn(async move {
        let mut ticker = tokio::time::interval(CHECK_EVERY);
        ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
        loop {
            ticker.tick().await;
            if let Err(e) = refresh_due(&state).await {
                tracing::warn!("Could not refresh subscribed calendars: {e:?}");
            }
        }
    });
}

async fn refresh_due(state: &AppState) -> AppResult<()> {
    let now = time::now();
    let due: Vec<(String, String)> = sqlx::query_as(
        "SELECT c.id, u.language FROM calendars c JOIN users u ON u.id = c.user_id
         WHERE c.kind = ? AND u.disabled = 0 AND (c.sync_attempted_at IS NULL
            OR (c.sync_error IS NULL AND c.sync_attempted_at <= ?)
            OR (c.sync_error IS NOT NULL AND c.sync_attempted_at <= ?))
         ORDER BY c.sync_attempted_at",
    )
    .bind(KIND)
    .bind(time::to_db(now - REFRESH_EVERY))
    .bind(time::to_db(now - RETRY_AFTER))
    .fetch_all(&state.db)
    .await?;
    for (id, language) in due {
        refresh(state, &id, Lang::from_tag(&language).unwrap_or_default()).await?;
    }
    Ok(())
}

#[cfg(test)]
#[path = "../test/subscriptions.rs"]
mod tests;
