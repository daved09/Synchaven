//! Two-factor authentication: TOTP codes (RFC 6238, SHA-1, 6 digits, 30 seconds – what every
//! authenticator app supports), recovery codes and app passwords for CardDAV.

use std::{
    collections::HashMap,
    sync::Mutex,
    time::{Duration, Instant},
};

use hmac::{Hmac, KeyInit, Mac};
use rand::RngCore;
use sha1::Sha1;
use sha2::{Digest, Sha256};

use crate::{
    error::{AppError, AppResult},
    i18n::tr,
    AppState,
};

const PERIOD: i64 = 30;
const DIGITS: u32 = 6;
/// Accepted deviation of the clock in time steps (±30 seconds)
const SKEW: i64 = 1;
/// Number of recovery codes that are issued together
pub const RECOVERY_CODES: usize = 10;
const ISSUER: &str = "Synchaven";

const BASE32: &[u8; 32] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
/// Alphabet of recovery codes and app passwords: lowercase, without characters that are easily confused (o, i, l, 1)
const CODE_ALPHABET: &[u8; 32] = b"abcdefghjkmnpqrstuvwxyz023456789";

fn random_bytes<const N: usize>() -> [u8; N] {
    let mut bytes = [0u8; N];
    rand::rngs::OsRng.fill_bytes(&mut bytes);
    bytes
}

/// RFC 4648 Base32 without padding
pub fn base32_encode(data: &[u8]) -> String {
    let mut out = String::with_capacity(data.len().div_ceil(5) * 8);
    let (mut buffer, mut bits) = (0u32, 0u32);
    for &b in data {
        buffer = (buffer << 8) | b as u32;
        bits += 8;
        while bits >= 5 {
            bits -= 5;
            out.push(BASE32[((buffer >> bits) & 31) as usize] as char);
        }
    }
    if bits > 0 {
        out.push(BASE32[((buffer << (5 - bits)) & 31) as usize] as char);
    }
    out
}

/// Base32 (case, spaces and padding are ignored); `None` for invalid characters
pub fn base32_decode(s: &str) -> Option<Vec<u8>> {
    let mut out = Vec::new();
    let (mut buffer, mut bits) = (0u32, 0u32);
    for c in s.chars().filter(|c| !c.is_whitespace() && *c != '=') {
        let v = BASE32.iter().position(|&b| b as char == c.to_ascii_uppercase())? as u32;
        buffer = (buffer << 5) | v;
        bits += 5;
        if bits >= 8 {
            bits -= 8;
            out.push((buffer >> bits) as u8);
        }
    }
    Some(out)
}

/// New secret: 160 bits as recommended by RFC 4226, Base32-encoded
pub fn generate_secret() -> String {
    base32_encode(&random_bytes::<20>())
}

/// Code for a time step (HOTP of RFC 4226 with the step as counter)
pub fn code_at(secret: &[u8], step: i64) -> u32 {
    let mut mac = Hmac::<Sha1>::new_from_slice(secret).expect("HMAC accepts keys of any length");
    mac.update(&step.to_be_bytes());
    let hash = mac.finalize().into_bytes();
    let offset = (hash[hash.len() - 1] & 0x0f) as usize;
    let value = u32::from_be_bytes([hash[offset], hash[offset + 1], hash[offset + 2], hash[offset + 3]]) & 0x7fff_ffff;
    value % 10u32.pow(DIGITS)
}

pub fn current_step() -> i64 {
    chrono::Utc::now().timestamp().div_euclid(PERIOD)
}

/// Is `code` a TOTP code (6 digits, spaces allowed)? Returns it without spaces.
fn as_totp(code: &str) -> Option<String> {
    let digits: String = code.chars().filter(|c| !c.is_whitespace()).collect();
    (digits.len() == DIGITS as usize && digits.chars().all(|c| c.is_ascii_digit())).then_some(digits)
}

/// Time step whose code matches – within ±[SKEW] steps around `now` and after `last_step`
/// (a code that was already used is rejected).
pub fn matching_step(secret_b32: &str, code: &str, now: i64, last_step: Option<i64>) -> Option<i64> {
    let code: u32 = as_totp(code)?.parse().ok()?;
    let secret = base32_decode(secret_b32)?;
    (now - SKEW..=now + SKEW)
        .filter(|step| last_step.is_none_or(|last| *step > last))
        .find(|step| code_at(&secret, *step) == code)
}

fn percent_encode(s: &str) -> String {
    s.bytes()
        .map(|b| match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'.' | b'_' | b'~' | b'@' => (b as char).to_string(),
            _ => format!("%{b:02X}"),
        })
        .collect()
}

/// `otpauth://` link for authenticator apps (Key URI Format of Google Authenticator)
pub fn otpauth_uri(secret: &str, account: &str) -> String {
    format!(
        "otpauth://totp/{issuer}:{account}?secret={secret}&issuer={issuer}&algorithm=SHA1&digits={DIGITS}&period={PERIOD}",
        issuer = percent_encode(ISSUER),
        account = percent_encode(account),
    )
}

/// QR code of `data` as an SVG document
pub fn qr_svg(data: &str) -> AppResult<String> {
    let code = qrcode::QrCode::new(data.as_bytes()).map_err(|e| AppError::Internal(anyhow::anyhow!("QR code: {e}")))?;
    Ok(code
        .render::<qrcode::render::svg::Color>()
        .min_dimensions(200, 200)
        .dark_color(qrcode::render::svg::Color("#000000"))
        .light_color(qrcode::render::svg::Color("#ffffff"))
        .build())
}

// ---------- Recovery codes and app passwords ----------

fn random_code(len: usize) -> String {
    let bytes = random_bytes::<32>();
    bytes.iter().take(len).map(|b| CODE_ALPHABET[(b & 31) as usize] as char).collect()
}

/// Removes separators and spaces and ignores case, so codes can be typed as shown or without dashes
pub fn normalize_code(code: &str) -> String {
    code.chars().filter(|c| c.is_ascii_alphanumeric()).map(|c| c.to_ascii_lowercase()).collect()
}

pub fn hash_code(code: &str) -> String {
    Sha256::digest(normalize_code(code).as_bytes()).iter().map(|b| format!("{b:02x}")).collect()
}

fn group(code: &str, size: usize) -> String {
    code.as_bytes().chunks(size).map(|c| std::str::from_utf8(c).unwrap()).collect::<Vec<_>>().join("-")
}

/// New recovery codes (shown as `xxxxx-xxxxx`, 50 bits each) and their hashes as a JSON array for the database
pub fn generate_recovery_codes() -> (Vec<String>, String) {
    let codes: Vec<String> = (0..RECOVERY_CODES).map(|_| group(&random_code(10), 5)).collect();
    let hashes: Vec<String> = codes.iter().map(|c| hash_code(c)).collect();
    (codes, serde_json::to_string(&hashes).unwrap())
}

/// New app password (shown as `xxxx-xxxx-xxxx-xxxx`, 80 bits) and its hash
pub fn generate_app_password() -> (String, String) {
    let password = group(&random_code(16), 4);
    let hash = hash_code(&password);
    (password, hash)
}

// ---------- Limiting failed attempts ----------

/// Failed code entries per account: six digits could otherwise be guessed quickly.
static FAILURES: Mutex<Option<HashMap<String, (u32, Instant)>>> = Mutex::new(None);
const MAX_FAILURES: u32 = 5;
const FAILURE_WINDOW: Duration = Duration::from_secs(5 * 60);

fn check_attempts(user_id: &str) -> AppResult<()> {
    let mut map = FAILURES.lock().unwrap_or_else(|e| e.into_inner());
    let map = map.get_or_insert_with(HashMap::new);
    match map.get(user_id) {
        Some((count, since)) if since.elapsed() < FAILURE_WINDOW && *count >= MAX_FAILURES => {
            Err(AppError::TooManyRequests(tr!("auth.totp_too_many_attempts")))
        }
        _ => Ok(()),
    }
}

fn record_attempt(user_id: &str, success: bool) {
    let mut map = FAILURES.lock().unwrap_or_else(|e| e.into_inner());
    let map = map.get_or_insert_with(HashMap::new);
    if success {
        map.remove(user_id);
        return;
    }
    let now = Instant::now();
    if map.len() > 10_000 {
        map.retain(|_, (_, since)| since.elapsed() < FAILURE_WINDOW);
    }
    let entry = map.entry(user_id.to_string()).or_insert((0, now));
    if entry.1.elapsed() >= FAILURE_WINDOW {
        *entry = (0, now);
    }
    entry.0 += 1;
}

/// Checks the second factor of an account with two-factor authentication on: a TOTP code of `stored_secret`
/// (as stored, i.e. encrypted) or an unused recovery code (which is then used up). Each code is accepted only once.
pub async fn verify_second_factor(state: &AppState, user_id: &str, stored_secret: &str, code: &str) -> AppResult<()> {
    check_attempts(user_id)?;
    let ok = if as_totp(code).is_some() {
        let secret = &state.secrets.decrypt(user_id, stored_secret).map_err(|e| {
            tracing::error!("Account {user_id}: {e:#}");
            AppError::Internal(e)
        })?;
        let last: Option<(Option<i64>,)> =
            sqlx::query_as("SELECT totp_last_step FROM users WHERE id = ?").bind(user_id).fetch_optional(&state.db).await?;
        let last = last.and_then(|(l,)| l);
        match matching_step(secret, code, current_step(), last) {
            // The condition in the UPDATE makes sure two simultaneous requests can't use the same code
            Some(step) => sqlx::query(
                "UPDATE users SET totp_last_step = ? WHERE id = ? AND (totp_last_step IS NULL OR totp_last_step < ?)",
            )
            .bind(step)
            .bind(user_id)
            .bind(step)
            .execute(&state.db)
            .await?
            .rows_affected()
                == 1,
            None => false,
        }
    } else {
        use_recovery_code(state, user_id, code).await?
    };
    record_attempt(user_id, ok);
    if ok {
        Ok(())
    } else {
        tracing::warn!("Account {user_id}: wrong two-factor code");
        Err(AppError::TotpInvalid(tr!("auth.totp_invalid")))
    }
}

async fn use_recovery_code(state: &AppState, user_id: &str, code: &str) -> AppResult<bool> {
    let hash = hash_code(code);
    let (stored,): (String,) =
        sqlx::query_as("SELECT totp_recovery_codes FROM users WHERE id = ?").bind(user_id).fetch_one(&state.db).await?;
    let mut hashes: Vec<String> = serde_json::from_str(&stored).unwrap_or_default();
    let Some(pos) = hashes.iter().position(|h| *h == hash) else { return Ok(false) };
    hashes.remove(pos);
    // Only if nobody else used a code in the meantime
    let used = sqlx::query("UPDATE users SET totp_recovery_codes = ? WHERE id = ? AND totp_recovery_codes = ?")
        .bind(serde_json::to_string(&hashes).unwrap())
        .bind(user_id)
        .bind(&stored)
        .execute(&state.db)
        .await?
        .rows_affected()
        == 1;
    if used {
        tracing::info!("Account {user_id}: recovery code used, {} left", hashes.len());
    }
    Ok(used)
}

/// Number of unused recovery codes in the stored JSON array
pub fn recovery_codes_left(stored: &str) -> usize {
    serde_json::from_str::<Vec<String>>(stored).map(|v| v.len()).unwrap_or(0)
}

#[cfg(test)]
#[path = "../test/totp.rs"]
mod tests;
