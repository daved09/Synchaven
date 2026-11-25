//! Encryption of database exports with a passphrase.
//!
//! An export contains every account's data, password hash and two-factor secret in plaintext. Encrypted with a
//! passphrase it can be stored like any other backup (another disk, a cloud drive) without handing all of that over.
//!
//! Format: a small JSON object, so the file stays recognizable and its parameters are visible:
//! `{ "format": "synchaven-export-encrypted", "format_version": 1, "kdf": {…}, "nonce": "…", "ciphertext": "…" }`.
//! The key is derived from the passphrase with Argon2id (salt and cost in `kdf`), the plaintext export is encrypted
//! with AES-256-GCM. Format, version and KDF parameters are authenticated along with it, so they can't be swapped.

use aes_gcm::{
    aead::{Aead, Payload},
    Aes256Gcm, KeyInit,
};
use anyhow::Context;
use argon2::{Algorithm, Argon2, Params, Version};
use base64::{engine::general_purpose::STANDARD as B64, Engine};
use rand::RngCore;
use serde::{Deserialize, Serialize};

use crate::{backup::ImportError, i18n::tr};

pub const FORMAT: &str = "synchaven-export-encrypted";
const FORMAT_VERSION: u32 = 1;
const ALGORITHM: &str = "argon2id";
/// Minimum length of a passphrase in characters
pub const MIN_PASSPHRASE_CHARS: usize = 12;

/// Argon2id cost for new exports: 64 MiB, 3 passes – roughly a second, once per export or import
const MEMORY_KIB: u32 = 64 * 1024;
const ITERATIONS: u32 = 3;
const PARALLELISM: u32 = 1;
/// Upper limits for files from elsewhere, so a crafted file can't make the server use gigabytes of memory
const MAX_MEMORY_KIB: u32 = 1024 * 1024;
const MAX_ITERATIONS: u32 = 20;
const MAX_PARALLELISM: u32 = 16;

const SALT_LEN: usize = 16;
const NONCE_LEN: usize = 12;

#[derive(Serialize, Deserialize)]
struct Encrypted {
    // `format` first: [is_encrypted] recognizes a file by its beginning
    format: String,
    format_version: u32,
    kdf: Kdf,
    nonce: String,
    ciphertext: String,
}

#[derive(Serialize, Deserialize)]
struct Kdf {
    algorithm: String,
    memory_kib: u32,
    iterations: u32,
    parallelism: u32,
    salt: String,
}

impl Kdf {
    /// Authenticated with the ciphertext: changed parameters make decryption fail
    fn aad(&self, version: u32) -> Vec<u8> {
        format!(
            "{FORMAT}:{version}:{}:{}:{}:{}:{}",
            self.algorithm, self.memory_kib, self.iterations, self.parallelism, self.salt
        )
        .into_bytes()
    }

    fn key(&self, passphrase: &str) -> anyhow::Result<[u8; 32]> {
        let salt = B64.decode(&self.salt).context("salt is not valid Base64")?;
        let params = Params::new(self.memory_kib, self.iterations, self.parallelism, Some(32))
            .map_err(|e| anyhow::anyhow!("invalid key derivation parameters: {e}"))?;
        let mut key = [0u8; 32];
        Argon2::new(Algorithm::Argon2id, Version::V0x13, params)
            .hash_password_into(passphrase.as_bytes(), &salt, &mut key)
            .map_err(|e| anyhow::anyhow!("key derivation failed: {e}"))?;
        Ok(key)
    }
}

/// Checks the length of a new passphrase.
pub fn check_passphrase(passphrase: &str) -> Result<(), String> {
    if passphrase.chars().count() < MIN_PASSPHRASE_CHARS {
        return Err(tr!("backup.passphrase_too_short", min = MIN_PASSPHRASE_CHARS));
    }
    Ok(())
}

/// Is `data` an encrypted export? Only looks at the beginning, the file can be large.
pub fn is_encrypted(data: &[u8]) -> bool {
    let head = &data[..data.len().min(256)];
    let needle = format!("\"{FORMAT}\"");
    head.windows(needle.len()).any(|w| w == needle.as_bytes())
}

/// Encrypts an export (its JSON) with `passphrase`. Compute-intensive: call it outside the async runtime.
pub fn encrypt(plain: &[u8], passphrase: &str) -> anyhow::Result<Vec<u8>> {
    encrypt_with_cost(plain, passphrase, MEMORY_KIB, ITERATIONS)
}

/// [encrypt] with a chosen Argon2id cost (tests use a cheap one)
fn encrypt_with_cost(plain: &[u8], passphrase: &str, memory_kib: u32, iterations: u32) -> anyhow::Result<Vec<u8>> {
    let mut salt = [0u8; SALT_LEN];
    let mut nonce = [0u8; NONCE_LEN];
    rand::rngs::OsRng.fill_bytes(&mut salt);
    rand::rngs::OsRng.fill_bytes(&mut nonce);
    let kdf = Kdf {
        algorithm: ALGORITHM.into(),
        memory_kib,
        iterations,
        parallelism: PARALLELISM,
        salt: B64.encode(salt),
    };
    let key = kdf.key(passphrase)?;
    let aad = kdf.aad(FORMAT_VERSION);
    let ciphertext = Aes256Gcm::new_from_slice(&key)
        .expect("32-byte key")
        .encrypt(&nonce.into(), Payload { msg: plain, aad: &aad })
        .map_err(|_| anyhow::anyhow!("encryption failed"))?;
    let out = Encrypted {
        format: FORMAT.into(),
        format_version: FORMAT_VERSION,
        kdf,
        nonce: B64.encode(nonce),
        ciphertext: B64.encode(ciphertext),
    };
    Ok(serde_json::to_vec_pretty(&out)?)
}

/// Decrypts an encrypted export; returns the plaintext export (JSON). Compute-intensive like [encrypt].
pub fn decrypt(data: &[u8], passphrase: &str) -> Result<Vec<u8>, ImportError> {
    let invalid = |e: String| ImportError::Invalid(tr!("backup.invalid_file", error = e));
    let file: Encrypted = serde_json::from_slice(data).map_err(|e| invalid(e.to_string()))?;
    if file.format != FORMAT {
        return Err(ImportError::Invalid(tr!("backup.wrong_format", format = format!("{:?}", file.format))));
    }
    if file.format_version > FORMAT_VERSION {
        return Err(ImportError::Invalid(tr!(
            "backup.newer_version",
            version = file.format_version,
            supported = FORMAT_VERSION
        )));
    }
    let k = &file.kdf;
    if k.algorithm != ALGORITHM
        || k.memory_kib > MAX_MEMORY_KIB
        || k.iterations > MAX_ITERATIONS
        || k.parallelism > MAX_PARALLELISM
    {
        return Err(invalid("unsupported key derivation parameters".into()));
    }
    let nonce: [u8; NONCE_LEN] = B64
        .decode(&file.nonce)
        .ok()
        .and_then(|n| n.try_into().ok())
        .ok_or_else(|| invalid("invalid nonce".into()))?;
    let ciphertext = B64.decode(&file.ciphertext).map_err(|e| invalid(e.to_string()))?;
    let key = k.key(passphrase).map_err(|e| invalid(e.to_string()))?;
    let aad = k.aad(file.format_version);
    Aes256Gcm::new_from_slice(&key)
        .expect("32-byte key")
        .decrypt(&nonce.into(), Payload { msg: &ciphertext, aad: &aad })
        // AES-GCM can't tell a wrong passphrase from a damaged file; the wrong passphrase is far more likely
        .map_err(|_| ImportError::Invalid(tr!("backup.wrong_passphrase")))
}

#[cfg(test)]
#[path = "../test/export_crypto.rs"]
mod tests;
