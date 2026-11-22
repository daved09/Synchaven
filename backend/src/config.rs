use std::{net::SocketAddr, path::PathBuf};

use anyhow::Context;
use rand::RngCore;

#[derive(Debug, Clone)]
pub struct Config {
    pub bind: SocketAddr,
    pub data_dir: PathBuf,
    pub static_dir: PathBuf,
    pub jwt_secret: String,
    pub token_ttl_days: i64,
    pub allow_registration: bool,
    pub cors_origins: Vec<String>,
}

fn env_or(key: &str, default: &str) -> String {
    std::env::var(key).ok().filter(|v| !v.trim().is_empty()).unwrap_or_else(|| default.to_string())
}

impl Config {
    pub fn from_env() -> anyhow::Result<Self> {
        let bind = env_or("BIND_ADDR", "0.0.0.0:8080").parse().context("BIND_ADDR ungültig")?;
        let data_dir = PathBuf::from(env_or("DATA_DIR", "./data"));
        std::fs::create_dir_all(&data_dir).context("DATA_DIR kann nicht angelegt werden")?;
        let static_dir = PathBuf::from(env_or("STATIC_DIR", "./static"));
        let token_ttl_days = env_or("TOKEN_TTL_DAYS", "30").parse().context("TOKEN_TTL_DAYS ungültig")?;
        let allow_registration = matches!(
            env_or("ALLOW_REGISTRATION", "true").to_lowercase().as_str(),
            "1" | "true" | "yes" | "ja"
        );
        let cors_origins = env_or("CORS_ORIGINS", "*")
            .split(',')
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty())
            .collect();

        let jwt_secret = match std::env::var("JWT_SECRET").ok().filter(|s| s.len() >= 32) {
            Some(s) => s,
            None => load_or_create_secret(&data_dir)?,
        };

        Ok(Self { bind, data_dir, static_dir, jwt_secret, token_ttl_days, allow_registration, cors_origins })
    }

    pub fn db_path(&self) -> PathBuf {
        self.data_dir.join("synchaven.db")
    }
}

/// Ohne JWT_SECRET wird ein zufälliges Secret im Datenverzeichnis abgelegt,
/// damit Tokens einen Neustart des Containers überleben.
fn load_or_create_secret(data_dir: &std::path::Path) -> anyhow::Result<String> {
    let path = data_dir.join("jwt_secret");
    if let Ok(s) = std::fs::read_to_string(&path) {
        let s = s.trim().to_string();
        if s.len() >= 32 {
            return Ok(s);
        }
    }
    let mut bytes = [0u8; 48];
    rand::thread_rng().fill_bytes(&mut bytes);
    let secret: String = bytes.iter().map(|b| format!("{b:02x}")).collect();
    std::fs::write(&path, &secret).with_context(|| {
        format!(
            "{} kann nicht gespeichert werden – hat der Prozess Schreibrechte für {}? \
             (Docker: PUID/PGID auf den Besitzer des Datenverzeichnisses setzen)",
            path.display(),
            data_dir.display()
        )
    })?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600));
    }
    tracing::info!("Neues JWT-Secret in {} erzeugt", path.display());
    Ok(secret)
}
