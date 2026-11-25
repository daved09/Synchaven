//! Encryption of the TOTP secrets in the database (AES-256-GCM).
//!
//! The key is kept outside the database: from `TOTP_ENCRYPTION_KEY` or, without it, in the file `totp_key` in the
//! data directory (created on first start). A copy of the database alone – a backup of `synchaven.db`, a stolen
//! disk image of just that file – therefore doesn't reveal the secrets of the authenticator apps.
//!
//! Stored format: `v1:` + Base64(nonce ‖ ciphertext). The account ID is authenticated along with it, so a value
//! can't be copied to another account. Values without the prefix are plaintext from older versions; they are
//! still read and get encrypted on the next server start ([encrypt_plaintext]).

use std::path::Path;

use aes_gcm::{
    aead::{Aead, Payload},
    Aes256Gcm, KeyInit,
};
use anyhow::{bail, Context};
use base64::{engine::general_purpose::STANDARD as B64, Engine};
use rand::RngCore;
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;

const PREFIX: &str = "v1:";
const NONCE_LEN: usize = 12;
const KEY_FILE: &str = "totp_key";
const KEY_ENV: &str = "TOTP_ENCRYPTION_KEY";
/// Minimum length of the key material from the environment variable
const MIN_KEY_LEN: usize = 32;

pub struct SecretBox(Aes256Gcm);

impl SecretBox {
    /// Key from any key material (environment variable or key file), via SHA-256 to 256 bits
    pub fn from_material(material: &str) -> Self {
        let key = Sha256::digest(material.trim().as_bytes());
        Self(Aes256Gcm::new_from_slice(&key).expect("32-byte key"))
    }

    /// Key from `TOTP_ENCRYPTION_KEY` or the key file; creates the file if neither exists.
    pub fn load_or_create(data_dir: &Path) -> anyhow::Result<Self> {
        if let Some(b) = Self::load(data_dir)? {
            return Ok(b);
        }
        let path = data_dir.join(KEY_FILE);
        let mut bytes = [0u8; 32];
        rand::rngs::OsRng.fill_bytes(&mut bytes);
        let material: String = bytes.iter().map(|b| format!("{b:02x}")).collect();
        std::fs::write(&path, &material).with_context(|| format!("Cannot save {}", path.display()))?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let _ = std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600));
        }
        tracing::info!("Generated new key for two-factor secrets in {}", path.display());
        Ok(Self::from_material(&material))
    }

    /// Key from `TOTP_ENCRYPTION_KEY` or the key file; `None` if neither exists.
    pub fn load(data_dir: &Path) -> anyhow::Result<Option<Self>> {
        if let Some(v) = std::env::var(KEY_ENV).ok().filter(|v| !v.trim().is_empty()) {
            if v.trim().len() < MIN_KEY_LEN {
                bail!("{KEY_ENV} must be at least {MIN_KEY_LEN} characters long");
            }
            return Ok(Some(Self::from_material(&v)));
        }
        let path = data_dir.join(KEY_FILE);
        match std::fs::read_to_string(&path) {
            Ok(s) if s.trim().len() >= MIN_KEY_LEN => Ok(Some(Self::from_material(&s))),
            Ok(_) => bail!("{} is too short – it must contain at least {MIN_KEY_LEN} characters", path.display()),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
            Err(e) => Err(e).with_context(|| format!("Cannot read {}", path.display())),
        }
    }

    /// Encrypts `plain` for the account `user_id`
    pub fn encrypt(&self, user_id: &str, plain: &str) -> String {
        let mut nonce = [0u8; NONCE_LEN];
        rand::rngs::OsRng.fill_bytes(&mut nonce);
        let ct = self
            .0
            .encrypt(&nonce.into(), Payload { msg: plain.as_bytes(), aad: user_id.as_bytes() })
            .expect("AES-GCM encryption does not fail for small inputs");
        let mut out = Vec::with_capacity(NONCE_LEN + ct.len());
        out.extend_from_slice(&nonce);
        out.extend_from_slice(&ct);
        format!("{PREFIX}{}", B64.encode(out))
    }

    /// Decrypts a stored value of `user_id`; plaintext from older versions is returned unchanged.
    pub fn decrypt(&self, user_id: &str, stored: &str) -> anyhow::Result<String> {
        let Some(encoded) = stored.strip_prefix(PREFIX) else { return Ok(stored.to_string()) };
        let raw = B64.decode(encoded).context("encrypted secret is not valid Base64")?;
        if raw.len() <= NONCE_LEN {
            bail!("encrypted secret is too short");
        }
        let (nonce, ct) = raw.split_at(NONCE_LEN);
        let nonce: [u8; NONCE_LEN] = nonce.try_into().expect("length checked");
        let plain = self
            .0
            .decrypt(&nonce.into(), Payload { msg: ct, aad: user_id.as_bytes() })
            .map_err(|_| anyhow::anyhow!("cannot decrypt secret – wrong key ({KEY_ENV} / {KEY_FILE})?"))?;
        String::from_utf8(plain).context("decrypted secret is not UTF-8")
    }
}

pub fn is_encrypted(stored: &str) -> bool {
    stored.starts_with(PREFIX)
}

/// Encrypts TOTP secrets that are still stored as plaintext (older versions, imports); returns their number.
pub async fn encrypt_plaintext(db: &SqlitePool, secrets: &SecretBox) -> anyhow::Result<u64> {
    let rows: Vec<(String, Option<String>, Option<String>)> = sqlx::query_as(
        "SELECT id, totp_secret, totp_pending_secret FROM users \
         WHERE (totp_secret IS NOT NULL AND totp_secret NOT LIKE 'v1:%') \
            OR (totp_pending_secret IS NOT NULL AND totp_pending_secret NOT LIKE 'v1:%')",
    )
    .fetch_all(db)
    .await?;
    let seal = |id: &str, v: Option<String>| v.map(|v| if is_encrypted(&v) { v } else { secrets.encrypt(id, &v) });
    for (id, secret, pending) in &rows {
        sqlx::query("UPDATE users SET totp_secret = ?, totp_pending_secret = ? WHERE id = ?")
            .bind(seal(id, secret.clone()))
            .bind(seal(id, pending.clone()))
            .bind(id)
            .execute(db)
            .await?;
    }
    Ok(rows.len() as u64)
}

#[cfg(test)]
#[path = "../test/crypto.rs"]
mod tests;
