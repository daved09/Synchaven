//! Settings the administrator can change in the web UI.
//!
//! They are stored in the `settings` table and kept in memory, so that e.g. issuing a token
//! doesn't need a database query. If an entry is missing, the value from the environment variable applies (`Config`).

use std::sync::RwLock;

use serde::Serialize;
use sqlx::SqlitePool;

use crate::config::Config;

const KEY_ALLOW_REGISTRATION: &str = "allow_registration";
const KEY_TOKEN_TTL_DAYS: &str = "token_ttl_days";

/// Allowed range for the session lifetime
pub const TOKEN_TTL_RANGE: std::ops::RangeInclusive<i64> = 1..=3650;

#[derive(Debug, Clone, Serialize)]
pub struct Settings {
    /// New accounts may register (the first account always can)
    pub allow_registration: bool,
    /// Lifetime of a sign-in token in days
    pub token_ttl_days: i64,
}

pub struct SettingsStore {
    current: RwLock<Settings>,
    /// Default values from the environment variables
    defaults: Settings,
}

impl SettingsStore {
    pub async fn load(db: &SqlitePool, config: &Config) -> anyhow::Result<Self> {
        let defaults = Settings { allow_registration: config.allow_registration, token_ttl_days: config.token_ttl_days };
        let store = Self { current: RwLock::new(defaults.clone()), defaults };
        store.reload(db).await?;
        Ok(store)
    }

    /// Reloads the table, e.g. after a database import.
    pub async fn reload(&self, db: &SqlitePool) -> anyhow::Result<()> {
        let rows: Vec<(String, String)> = sqlx::query_as("SELECT key, value FROM settings").fetch_all(db).await?;
        let mut s = self.defaults.clone();
        for (key, value) in rows {
            match key.as_str() {
                KEY_ALLOW_REGISTRATION => s.allow_registration = value == "true",
                KEY_TOKEN_TTL_DAYS => {
                    if let Some(v) = value.parse().ok().filter(|v| TOKEN_TTL_RANGE.contains(v)) {
                        s.token_ttl_days = v;
                    }
                }
                _ => {}
            }
        }
        *self.current.write().unwrap_or_else(|e| e.into_inner()) = s;
        Ok(())
    }

    pub fn get(&self) -> Settings {
        self.current.read().unwrap_or_else(|e| e.into_inner()).clone()
    }

    pub fn defaults(&self) -> &Settings {
        &self.defaults
    }

    /// Stores the given values; `None` leaves the value unchanged.
    pub async fn update(
        &self,
        db: &SqlitePool,
        allow_registration: Option<bool>,
        token_ttl_days: Option<i64>,
    ) -> anyhow::Result<Settings> {
        let mut tx = db.begin().await?;
        let upsert = "INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value";
        if let Some(v) = allow_registration {
            sqlx::query(upsert).bind(KEY_ALLOW_REGISTRATION).bind(v.to_string()).execute(&mut *tx).await?;
        }
        if let Some(v) = token_ttl_days {
            sqlx::query(upsert).bind(KEY_TOKEN_TTL_DAYS).bind(v.to_string()).execute(&mut *tx).await?;
        }
        tx.commit().await?;
        self.reload(db).await?;
        Ok(self.get())
    }
}
