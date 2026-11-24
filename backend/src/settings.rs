//! Einstellungen, die der Administrator in der Weboberfläche ändern kann.
//!
//! Sie liegen in der Tabelle `settings` und werden im Speicher gehalten, damit z. B. das Ausstellen eines Tokens
//! keine Datenbankabfrage braucht. Fehlt ein Eintrag, gilt der Wert aus der Umgebungsvariablen (`Config`).

use std::sync::RwLock;

use serde::Serialize;
use sqlx::SqlitePool;

use crate::config::Config;

const KEY_ALLOW_REGISTRATION: &str = "allow_registration";
const KEY_TOKEN_TTL_DAYS: &str = "token_ttl_days";

/// Erlaubter Bereich für die Gültigkeit der Anmeldung
pub const TOKEN_TTL_RANGE: std::ops::RangeInclusive<i64> = 1..=3650;

#[derive(Debug, Clone, Serialize)]
pub struct Settings {
    /// Neue Konten dürfen sich registrieren (das erste Konto geht immer)
    pub allow_registration: bool,
    /// Gültigkeit eines Anmelde-Tokens in Tagen
    pub token_ttl_days: i64,
}

pub struct SettingsStore {
    current: RwLock<Settings>,
    /// Standardwerte aus den Umgebungsvariablen
    defaults: Settings,
}

impl SettingsStore {
    pub async fn load(db: &SqlitePool, config: &Config) -> anyhow::Result<Self> {
        let defaults = Settings { allow_registration: config.allow_registration, token_ttl_days: config.token_ttl_days };
        let store = Self { current: RwLock::new(defaults.clone()), defaults };
        store.reload(db).await?;
        Ok(store)
    }

    /// Liest die Tabelle neu ein, z. B. nach einem Datenbank-Import.
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

    /// Speichert die übergebenen Werte; `None` lässt den Wert unverändert.
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
