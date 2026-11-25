//! Translation of user-facing texts (API error messages, import reports, default names).
//!
//! The texts live in one file per language in `locales/` (`en.json`, `de.json`) and are loaded
//! with rust-i18n at compile time. In the code they are referenced by key with [`tr!`], which picks
//! the language of the current request. The language comes from the
//! `Accept-Language` header (see [`detect`]); without one, English is used. Log output and the
//! command line stay English.
//!
//! Texts that are stored in the database (default calendars, lists and address books, birthday
//! events) use the language saved for the user ([`user_lang`]), since they are also generated without
//! a request. It is chosen on registration (or by the administrator who creates the account) and can be
//! changed in the profile; without a choice, the request's language is used.

use axum::{extract::Request, http::header, middleware::Next, response::Response};
use sqlx::SqliteConnection;

use crate::error::AppResult;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum Lang {
    #[default]
    En,
    De,
}

impl Lang {
    pub fn code(self) -> &'static str {
        match self {
            Lang::En => "en",
            Lang::De => "de",
        }
    }

    /// Language from a language tag (`de`, `de-AT`, `en_US` …); `None` if not supported.
    pub fn from_tag(tag: &str) -> Option<Lang> {
        let primary = tag.trim().split(['-', '_']).next()?.to_ascii_lowercase();
        match primary.as_str() {
            "en" => Some(Lang::En),
            "de" => Some(Lang::De),
            _ => None,
        }
    }

    /// The most preferred supported language of an `Accept-Language` header.
    pub fn from_accept_language(header: &str) -> Option<Lang> {
        let mut entries: Vec<(f32, Lang)> = header
            .split(',')
            .filter_map(|part| {
                let mut it = part.split(';');
                let lang = Lang::from_tag(it.next()?)?;
                let q = it
                    .find_map(|p| p.trim().strip_prefix("q="))
                    .map_or(Some(1.0), |q| q.trim().parse::<f32>().ok())?;
                (q > 0.0).then_some((q, lang))
            })
            .collect();
        // Stable sort: equal weights keep the order of the header.
        entries.sort_by(|a, b| b.0.total_cmp(&a.0));
        entries.first().map(|e| e.1)
    }
}

tokio::task_local! {
    /// Language requested by the client (`None`: no supported language in the header)
    static REQUEST_LANG: Option<Lang>;
}

/// Middleware: runs the request with the language from its `Accept-Language` header.
pub async fn detect(req: Request, next: Next) -> Response {
    let lang = req
        .headers()
        .get(header::ACCEPT_LANGUAGE)
        .and_then(|v| v.to_str().ok())
        .and_then(Lang::from_accept_language);
    REQUEST_LANG.scope(lang, next.run(req)).await
}

/// Language requested by the current request, if it named a supported one.
pub fn requested() -> Option<Lang> {
    REQUEST_LANG.try_with(|l| *l).ok().flatten()
}

/// Language chosen explicitly in a request body (`"de"`, `"en"`); `None` if not given.
pub fn parse_choice(code: Option<&str>) -> AppResult<Option<Lang>> {
    match code.map(str::trim).filter(|c| !c.is_empty()) {
        None => Ok(None),
        Some(c) => match c {
            "de" => Ok(Some(Lang::De)),
            "en" => Ok(Some(Lang::En)),
            other => Err(crate::error::AppError::BadRequest(tr!("validation.unsupported_language", language = other))),
        },
    }
}

/// Language for texts of the current request (English outside of requests).
pub fn current() -> Lang {
    requested().unwrap_or_default()
}

/// Language saved for a user (for texts that are stored in the database).
pub async fn user_lang(conn: &mut SqliteConnection, user_id: &str) -> AppResult<Lang> {
    let code: Option<(String,)> =
        sqlx::query_as("SELECT language FROM users WHERE id = ?").bind(user_id).fetch_optional(conn).await?;
    Ok(code.and_then(|(c,)| Lang::from_tag(&c)).unwrap_or_default())
}

/// `tr!("event.unknown_time_zone", tz = name)` – text in the language of the current request.
macro_rules! tr {
    ($key:literal $(, $name:ident = $value:expr)* $(,)?) => {
        $crate::i18n::tr_in!($crate::i18n::current(), $key $(, $name = $value)*)
    };
}

/// `tr_in!(lang, "birthdays.born_on", date = d)` – text in the given language.
macro_rules! tr_in {
    ($lang:expr, $key:literal $(, $name:ident = $value:expr)* $(,)?) => {
        ::rust_i18n::t!($key, locale = $lang.code() $(, $name = $value)*).into_owned()
    };
}

pub(crate) use {tr, tr_in};

#[cfg(test)]
#[path = "../test/i18n.rs"]
mod tests;
