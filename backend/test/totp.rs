use super::*;

/// Secret of the test vectors in RFC 6238, appendix B (SHA-1): ASCII "12345678901234567890"
const RFC_SECRET: &[u8] = b"12345678901234567890";

#[test]
fn rfc6238_test_vectors() {
    // Times from the RFC; the vectors have 8 digits, our codes are their last 6
    for (time, expected) in [
        (59i64, 94287082u32),
        (1111111109, 7081804),
        (1111111111, 14050471),
        (1234567890, 89005924),
        (2000000000, 69279037),
        (20000000000, 65353130),
    ] {
        assert_eq!(code_at(RFC_SECRET, time / PERIOD), expected % 1_000_000, "time {time}");
    }
}

#[test]
fn base32_round_trip() {
    assert_eq!(base32_encode(b"foobar"), "MZXW6YTBOI");
    assert_eq!(base32_decode("MZXW6YTBOI").unwrap(), b"foobar");
    assert_eq!(base32_decode("mzxw 6ytb oi======").unwrap(), b"foobar");
    assert!(base32_decode("MZXW1").is_none());
    let secret = generate_secret();
    assert_eq!(secret.len(), 32);
    assert_eq!(base32_decode(&secret).unwrap().len(), 20);
}

#[test]
fn matching_step_accepts_skew_and_rejects_reuse() {
    let secret = base32_encode(RFC_SECRET);
    let now = 1_000_000;
    let code = |step| format!("{:06}", code_at(RFC_SECRET, step));
    assert_eq!(matching_step(&secret, &code(now), now, None), Some(now));
    assert_eq!(matching_step(&secret, &code(now - 1), now, None), Some(now - 1));
    assert_eq!(matching_step(&secret, &code(now + 1), now, None), Some(now + 1));
    assert_eq!(matching_step(&secret, &code(now - 2), now, None), None);
    // With spaces as some apps show it
    let c = code(now);
    assert_eq!(matching_step(&secret, &format!("{} {}", &c[..3], &c[3..]), now, None), Some(now));
    // Already used
    assert_eq!(matching_step(&secret, &code(now), now, Some(now)), None);
    assert_eq!(matching_step(&secret, &code(now), now, Some(now - 1)), Some(now));
    assert_eq!(matching_step(&secret, "12345", now, None), None);
    assert_eq!(matching_step(&secret, "abcdef", now, None), None);
}

#[test]
fn otpauth_uri_is_encoded() {
    let uri = otpauth_uri("ABC", "anna+test@example.org");
    assert_eq!(
        uri,
        "otpauth://totp/Synchaven:anna%2Btest@example.org?secret=ABC&issuer=Synchaven&algorithm=SHA1&digits=6&period=30"
    );
    assert!(qr_svg(&uri).unwrap().starts_with("<?xml"));
}

#[test]
fn recovery_codes_and_app_passwords() {
    let (codes, json) = generate_recovery_codes();
    assert_eq!(codes.len(), RECOVERY_CODES);
    assert_eq!(recovery_codes_left(&json), RECOVERY_CODES);
    let first = &codes[0];
    assert_eq!(first.len(), 11);
    assert_eq!(&first[5..6], "-");
    // Typed without dash and in capitals: same hash
    assert_eq!(hash_code(&first.replace('-', "").to_uppercase()), hash_code(first));
    assert!(json.contains(&hash_code(first)));

    let (password, hash) = generate_app_password();
    assert_eq!(password.len(), 19);
    assert_eq!(password.split('-').count(), 4);
    assert_eq!(hash, hash_code(&password));
    assert!(password.chars().all(|c| c == '-' || CODE_ALPHABET.contains(&(c as u8))));
}

#[test]
fn too_many_failures_block_further_attempts() {
    let user = "totp-test-user";
    for _ in 0..MAX_FAILURES {
        assert!(check_attempts(user).is_ok());
        record_attempt(user, false);
    }
    assert!(matches!(check_attempts(user), Err(AppError::TooManyRequests(_))));
    record_attempt(user, true);
    assert!(check_attempts(user).is_ok());
}
