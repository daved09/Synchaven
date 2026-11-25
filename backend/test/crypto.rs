use super::*;

#[test]
fn round_trip_and_binding_to_account() {
    let b = SecretBox::from_material("0123456789abcdef0123456789abcdef");
    let enc = b.encrypt("user-1", "JBSWY3DPEHPK3PXP");
    assert!(is_encrypted(&enc));
    assert!(!enc.contains("JBSWY3DPEHPK3PXP"));
    assert_eq!(b.decrypt("user-1", &enc).unwrap(), "JBSWY3DPEHPK3PXP");
    // Copied to another account it is useless
    assert!(b.decrypt("user-2", &enc).is_err());
    // Random nonce: the same secret gives different values
    assert_ne!(enc, b.encrypt("user-1", "JBSWY3DPEHPK3PXP"));
}

#[test]
fn wrong_key_fails_and_plaintext_passes() {
    let a = SecretBox::from_material("0123456789abcdef0123456789abcdef");
    let b = SecretBox::from_material("fedcba9876543210fedcba9876543210");
    let enc = a.encrypt("u", "SECRET");
    assert!(b.decrypt("u", &enc).is_err());
    assert_eq!(a.decrypt("u", "JBSWY3DPEHPK3PXP").unwrap(), "JBSWY3DPEHPK3PXP");
    assert!(a.decrypt("u", "v1:!!").is_err());
    assert!(a.decrypt("u", "v1:AAAA").is_err());
}
