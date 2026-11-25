use super::*;

const EXPORT: &[u8] = br#"{"format":"synchaven-export","format_version":1,"tables":{}}"#;

/// Cheap Argon2id cost, so the tests stay fast in debug builds
fn enc(pass: &str) -> Vec<u8> {
    encrypt_with_cost(EXPORT, pass, 64, 1).unwrap()
}

#[test]
fn round_trip() {
    let file = enc("correct horse battery staple");
    assert!(is_encrypted(&file));
    assert!(!is_encrypted(EXPORT));
    // Nothing of the plaintext is visible
    assert!(!String::from_utf8_lossy(&file).contains("tables"));
    assert_eq!(decrypt(&file, "correct horse battery staple").unwrap(), EXPORT);
}

#[test]
fn wrong_passphrase_fails() {
    let file = enc("correct horse battery staple");
    assert!(matches!(decrypt(&file, "wrong passphrase!!"), Err(ImportError::Invalid(_))));
}

#[test]
fn changed_parameters_fail() {
    let file = enc("correct horse battery staple");
    let mut v: serde_json::Value = serde_json::from_slice(&file).unwrap();
    // Same passphrase, but the stored cost was changed: the derived key differs and the AAD no longer matches
    v["kdf"]["iterations"] = 2.into();
    let changed = serde_json::to_vec(&v).unwrap();
    assert!(decrypt(&changed, "correct horse battery staple").is_err());
}

#[test]
fn excessive_cost_is_rejected() {
    let file = enc("correct horse battery staple");
    let mut v: serde_json::Value = serde_json::from_slice(&file).unwrap();
    v["kdf"]["memory_kib"] = (MAX_MEMORY_KIB + 1).into();
    assert!(decrypt(&serde_json::to_vec(&v).unwrap(), "correct horse battery staple").is_err());
}

#[test]
fn random_salt_and_nonce() {
    assert_ne!(enc("correct horse battery staple"), enc("correct horse battery staple"));
}

#[test]
fn passphrase_length() {
    assert!(check_passphrase("short").is_err());
    assert!(check_passphrase("twelve chars").is_ok());
}
