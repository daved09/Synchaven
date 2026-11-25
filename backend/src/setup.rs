//! Initial setup: as long as there is no account, the first one – the administrator – can only be created with a
//! one-time setup token. It is written to the server log and to the file `setup_token` in the data directory
//! (or set with `SETUP_TOKEN`), so only someone with access to the server can claim the administrator account,
//! even if the server is already reachable from the internet. Once the administrator exists, the token is deleted;
//! further accounts need open registration or are created in the admin page.

use std::path::{Path, PathBuf};

use anyhow::Context;
use rand::RngCore;
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;

const FILE: &str = "setup_token";
const ENV: &str = "SETUP_TOKEN";
const MIN_LEN: usize = 16;

pub struct Setup {
    path: PathBuf,
    /// Lock around creating and deleting the file
    lock: tokio::sync::Mutex<()>,
}

/// Is there no account yet (setup pending)?
pub async fn pending(db: &SqlitePool) -> sqlx::Result<bool> {
    let any: Option<(i64,)> = sqlx::query_as("SELECT 1 FROM users LIMIT 1").fetch_optional(db).await?;
    Ok(any.is_none())
}

fn from_env() -> Option<String> {
    std::env::var(ENV).ok().map(|v| v.trim().to_string()).filter(|v| v.len() >= MIN_LEN)
}

/// Comparison in constant time (over the hashes), so the response time reveals nothing about the token
fn same(a: &str, b: &str) -> bool {
    let (a, b) = (Sha256::digest(a.trim().as_bytes()), Sha256::digest(b.trim().as_bytes()));
    a.iter().zip(b.iter()).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

impl Setup {
    pub fn new(data_dir: &Path) -> Self {
        if std::env::var(ENV).is_ok_and(|v| !v.trim().is_empty() && v.trim().len() < MIN_LEN) {
            tracing::warn!("{ENV} is ignored: it must be at least {MIN_LEN} characters long");
        }
        Self { path: data_dir.join(FILE), lock: tokio::sync::Mutex::new(()) }
    }

    /// At startup and whenever the database is empty: makes sure a token exists and shows it in the log;
    /// with accounts present, removes a leftover token file.
    pub async fn prepare(&self, db: &SqlitePool) -> anyhow::Result<()> {
        if pending(db).await? {
            if from_env().is_some() {
                tracing::warn!("Initial setup: no account exists yet. Create the administrator account in the web UI with the token from {ENV}");
            } else {
                let (token, created) = self.token().await?;
                // A new token was already announced when it was created
                if !created {
                    announce(&token, &self.path);
                }
            }
        } else {
            self.finish().await;
        }
        Ok(())
    }

    /// The current token and whether it was just created (then it was logged)
    async fn token(&self) -> anyhow::Result<(String, bool)> {
        if let Some(t) = from_env() {
            return Ok((t, false));
        }
        let _guard = self.lock.lock().await;
        if let Ok(t) = std::fs::read_to_string(&self.path) {
            if t.trim().len() >= MIN_LEN {
                return Ok((t.trim().to_string(), false));
            }
        }
        let mut bytes = [0u8; 16];
        rand::rngs::OsRng.fill_bytes(&mut bytes);
        let token: String = bytes.iter().map(|b| format!("{b:02x}")).collect();
        std::fs::write(&self.path, &token).with_context(|| format!("Cannot save {}", self.path.display()))?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let _ = std::fs::set_permissions(&self.path, std::fs::Permissions::from_mode(0o600));
        }
        announce(&token, &self.path);
        Ok((token, true))
    }

    /// Is `given` the valid setup token?
    pub async fn check(&self, given: Option<&str>) -> anyhow::Result<bool> {
        let Some(given) = given.map(str::trim).filter(|g| !g.is_empty()) else { return Ok(false) };
        Ok(same(given, &self.token().await?.0))
    }

    /// Setup done: the token is no longer valid
    pub async fn finish(&self) {
        let _guard = self.lock.lock().await;
        match std::fs::remove_file(&self.path) {
            Ok(()) => tracing::info!("Initial setup finished, setup token deleted"),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
            Err(e) => tracing::warn!("Cannot delete {}: {e}", self.path.display()),
        }
    }
}

fn announce(token: &str, path: &Path) {
    tracing::warn!(
        "Initial setup: no account exists yet. Create the administrator account in the web UI with the \
         setup token {token} (also in {})",
        path.display()
    );
}

#[cfg(test)]
#[path = "../test/setup.rs"]
mod tests;
