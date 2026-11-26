use super::*;

const SECRET: &str = "test-secret-0123456789abcdef0123456789abcdef";

#[test]
fn tokens_carry_their_issue_time_in_milliseconds() {
    let keys = JwtKeys::new(SECRET);
    let before = Utc::now().timestamp_millis();
    let (token, _) = keys.issue("user-1", 30).unwrap();
    let after = Utc::now().timestamp_millis();

    let (id, issued_at) = keys.verify(&token).unwrap();
    assert_eq!(id, "user-1");
    assert!((before..=after).contains(&issued_at), "{before} <= {issued_at} <= {after}");
}

#[test]
fn tokens_of_older_versions_count_from_the_start_of_their_second() {
    let keys = JwtKeys::new(SECRET);
    let iat = Utc::now().timestamp();
    let claims = Claims { sub: "user-1".into(), iat, exp: iat + 3600, iat_ms: None };
    let token = encode(&Header::default(), &claims, &keys.encoding).unwrap();

    assert_eq!(keys.verify(&token).unwrap().1, iat * 1000);
}

#[test]
fn sign_out_ends_tokens_issued_earlier_in_the_same_second() {
    let second = 1_800_000_000_000;
    // Issued 300 ms before the sign-out, in the same second: ended
    assert!(revoked(second + 100, Some(second + 400)));
    // Issued after it (e.g. the new token of a password change): valid
    assert!(!revoked(second + 400, Some(second + 400)));
    assert!(!revoked(second + 500, Some(second + 400)));
    // Never signed out
    assert!(!revoked(second, None));
}

#[test]
fn tokens_from_another_secret_are_rejected() {
    let (token, _) = JwtKeys::new(SECRET).issue("user-1", 30).unwrap();
    assert!(JwtKeys::new("another-secret-0123456789abcdef0123456789ab").verify(&token).is_err());
}
