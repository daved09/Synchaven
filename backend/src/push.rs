//! Web Push (RFC 8030) with VAPID (RFC 8292) and encrypted payload (RFC 8291, aes128gcm).
//!
//! The server signs every request with its own P-256 key (VAPID). The key is
//! stored in the data directory on first start; if it changes, all subscriptions become invalid.

use std::{path::Path, time::Duration};

use aes_gcm::{aead::Aead, Aes128Gcm, KeyInit};
use anyhow::Context;
use base64::{engine::general_purpose::URL_SAFE_NO_PAD as B64, Engine};
use hkdf::Hkdf;
use p256::{
    ecdsa::{signature::Signer, Signature, SigningKey},
    elliptic_curve::sec1::ToSec1Point,
    PublicKey, SecretKey,
};
use rand::RngCore;
use sha2::Sha256;

/// Record size (rs) – the payload always fits into a single record.
const RECORD_SIZE: u32 = 4096;
/// Maximum plaintext size of a push message (4096 − 16 tag − 1 delimiter byte − 86 header)
const MAX_PAYLOAD: usize = 3993;

pub struct WebPush {
    key: SigningKey,
    /// Public key (uncompressed point, Base64url) – `applicationServerKey` in the browser
    pub public_key: String,
    subject: String,
    client: reqwest::Client,
}

/// Target of a push message, as returned by the browser from `pushManager.subscribe()`.
pub struct Subscription<'a> {
    pub endpoint: &'a str,
    pub p256dh: &'a str,
    pub auth: &'a str,
}

#[derive(Debug, PartialEq, Eq)]
pub enum Outcome {
    Sent,
    /// The subscription no longer exists (404/410) and should be deleted.
    Gone,
}

impl WebPush {
    pub fn load_or_create(data_dir: &Path, subject: String) -> anyhow::Result<Self> {
        let path = data_dir.join("vapid_private_key");
        let secret = match std::fs::read_to_string(&path) {
            Ok(s) => {
                let bytes = B64.decode(s.trim()).context("vapid_private_key ist kein gültiges Base64url")?;
                SecretKey::from_slice(&bytes).context("vapid_private_key ist kein gültiger P-256-Schlüssel")?
            }
            Err(_) => {
                let secret = random_secret();
                std::fs::write(&path, B64.encode(secret.to_bytes()))
                    .with_context(|| format!("{} kann nicht gespeichert werden", path.display()))?;
                #[cfg(unix)]
                {
                    use std::os::unix::fs::PermissionsExt;
                    let _ = std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600));
                }
                tracing::info!("Neuer VAPID-Schlüssel für Web Push in {} erzeugt", path.display());
                secret
            }
        };
        let public_key = B64.encode(secret.public_key().to_sec1_point(false).as_bytes());
        let client = reqwest::Client::builder()
            .timeout(Duration::from_secs(15))
            // Endpoints come from the browser; redirects are not followed.
            .redirect(reqwest::redirect::Policy::none())
            .user_agent(concat!("Synchaven/", env!("SYNCHAVEN_VERSION")))
            .build()?;
        Ok(Self { key: SigningKey::from(&secret), public_key, subject, client })
    }

    /// VAPID JWT (ES256) for the push service at `audience` (scheme + host of the endpoint).
    fn vapid_jwt(&self, audience: &str) -> String {
        let header = B64.encode(br#"{"typ":"JWT","alg":"ES256"}"#);
        let exp = chrono::Utc::now().timestamp() + 12 * 3600;
        let claims = serde_json::json!({ "aud": audience, "exp": exp, "sub": self.subject });
        let input = format!("{header}.{}", B64.encode(claims.to_string()));
        let sig: Signature = self.key.sign(input.as_bytes());
        format!("{input}.{}", B64.encode(sig.to_bytes()))
    }

    /// Encrypts `payload` for the subscription and sends it to the push service.
    /// `ttl`: how long the service may try to deliver the message if the browser is offline.
    pub async fn send(&self, sub: &Subscription<'_>, payload: &[u8], ttl: Duration) -> anyhow::Result<Outcome> {
        let url = reqwest::Url::parse(sub.endpoint).context("Ungültiger Push-Endpunkt")?;
        let audience = url.origin().ascii_serialization();
        let body = encrypt(payload, sub.p256dh, sub.auth, &random_secret(), &random_salt())?;
        let res = self
            .client
            .post(url)
            .header("TTL", ttl.as_secs().to_string())
            .header("Urgency", "high")
            .header("Content-Encoding", "aes128gcm")
            .header("Content-Type", "application/octet-stream")
            .header("Authorization", format!("vapid t={}, k={}", self.vapid_jwt(&audience), self.public_key))
            .body(body)
            .send()
            .await?;
        let status = res.status();
        if status.is_success() {
            return Ok(Outcome::Sent);
        }
        if status == reqwest::StatusCode::NOT_FOUND || status == reqwest::StatusCode::GONE {
            return Ok(Outcome::Gone);
        }
        let text = res.text().await.unwrap_or_default();
        anyhow::bail!("Push-Dienst antwortet mit {status}: {}", text.chars().take(300).collect::<String>())
    }
}

fn random_secret() -> SecretKey {
    loop {
        let mut bytes = [0u8; 32];
        rand::thread_rng().fill_bytes(&mut bytes);
        if let Ok(key) = SecretKey::from_slice(&bytes) {
            return key;
        }
    }
}

fn random_salt() -> [u8; 16] {
    let mut salt = [0u8; 16];
    rand::thread_rng().fill_bytes(&mut salt);
    salt
}

fn hkdf_expand(salt: &[u8], ikm: &[u8], info: &[u8], out: &mut [u8]) {
    Hkdf::<Sha256>::new(Some(salt), ikm).expand(info, out).expect("HKDF-Länge gültig");
}

/// Encryption according to RFC 8291 (content encoding `aes128gcm`, one record).
/// `sender` is the server's ephemeral key, `salt` 16 random bytes.
fn encrypt(payload: &[u8], p256dh: &str, auth: &str, sender: &SecretKey, salt: &[u8; 16]) -> anyhow::Result<Vec<u8>> {
    anyhow::ensure!(payload.len() <= MAX_PAYLOAD, "Push-Nachricht zu groß");
    let ua_public_bytes = B64.decode(p256dh.trim_end_matches('=')).context("p256dh ungültig")?;
    let ua_public = PublicKey::from_sec1_bytes(&ua_public_bytes).context("p256dh ist kein P-256-Punkt")?;
    let auth_secret = B64.decode(auth.trim_end_matches('=')).context("auth ungültig")?;
    let as_public = sender.public_key().to_sec1_point(false);
    let as_public = as_public.as_bytes();

    let shared = p256::ecdh::diffie_hellman(sender.to_nonzero_scalar(), ua_public.as_affine());

    let mut key_info = b"WebPush: info\0".to_vec();
    key_info.extend_from_slice(&ua_public_bytes);
    key_info.extend_from_slice(as_public);
    let mut ikm = [0u8; 32];
    hkdf_expand(&auth_secret, shared.raw_secret_bytes(), &key_info, &mut ikm);

    let mut cek = [0u8; 16];
    let mut nonce = [0u8; 12];
    hkdf_expand(salt, &ikm, b"Content-Encoding: aes128gcm\0", &mut cek);
    hkdf_expand(salt, &ikm, b"Content-Encoding: nonce\0", &mut nonce);

    let mut plain = payload.to_vec();
    plain.push(2); // delimiter byte of the last record, no padding
    let cipher = Aes128Gcm::new_from_slice(&cek).expect("16-Byte-Schlüssel");
    let encrypted = cipher
        .encrypt(&nonce.into(), plain.as_slice())
        .map_err(|_| anyhow::anyhow!("Verschlüsselung fehlgeschlagen"))?;

    let mut body = Vec::with_capacity(16 + 4 + 1 + as_public.len() + encrypted.len());
    body.extend_from_slice(salt);
    body.extend_from_slice(&RECORD_SIZE.to_be_bytes());
    body.push(as_public.len() as u8);
    body.extend_from_slice(as_public);
    body.extend_from_slice(&encrypted);
    Ok(body)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Example from RFC 8291, section 5
    #[test]
    fn rfc8291_example() {
        let sender = SecretKey::from_slice(&B64.decode("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw").unwrap()).unwrap();
        let salt: [u8; 16] = B64.decode("DGv6ra1nlYgDCS1FRnbzlw").unwrap().try_into().unwrap();
        let body = encrypt(
            b"When I grow up, I want to be a watermelon",
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4",
            "BTBZMqHH6r4Tts7J_aSIgg",
            &sender,
            &salt,
        )
        .unwrap();
        assert_eq!(
            B64.encode(body),
            "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN"
        );
    }

    #[test]
    fn vapid_jwt_verifies() {
        use p256::ecdsa::{signature::Verifier, VerifyingKey};
        let secret = random_secret();
        let push = WebPush {
            key: SigningKey::from(&secret),
            public_key: String::new(),
            subject: "mailto:test@example.org".into(),
            client: reqwest::Client::new(),
        };
        let jwt = push.vapid_jwt("https://push.example.net");
        let (input, sig) = jwt.rsplit_once('.').unwrap();
        let sig = Signature::from_slice(&B64.decode(sig).unwrap()).unwrap();
        VerifyingKey::from(&SigningKey::from(&secret)).verify(input.as_bytes(), &sig).unwrap();
        let claims: serde_json::Value =
            serde_json::from_slice(&B64.decode(input.split('.').nth(1).unwrap()).unwrap()).unwrap();
        assert_eq!(claims["aud"], "https://push.example.net");
    }
}
