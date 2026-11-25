use super::*;

#[test]
fn free_attempts_then_growing_delay() {
    let t = Throttle::default();
    let now = Instant::now();
    for _ in 0..FREE_ATTEMPTS - 1 {
        t.failure_at("a@example.org", now);
        assert!(t.check_at("a@example.org", now).is_ok());
    }
    t.failure_at("a@example.org", now);
    assert_eq!(t.check_at("a@example.org", now), Err(BASE_DELAY));
    assert!(t.check_at("a@example.org", now + BASE_DELAY).is_ok());

    // Each further failure doubles the waiting time …
    t.failure_at("a@example.org", now + BASE_DELAY);
    assert_eq!(t.check_at("a@example.org", now + BASE_DELAY), Err(BASE_DELAY * 2));
    // … up to the maximum
    for _ in 0..20 {
        t.failure_at("a@example.org", now + BASE_DELAY);
    }
    assert_eq!(t.check_at("a@example.org", now + BASE_DELAY), Err(MAX_DELAY));
}

#[test]
fn success_resets_and_addresses_are_separate() {
    let t = Throttle::default();
    let now = Instant::now();
    for _ in 0..FREE_ATTEMPTS {
        t.failure_at("a@example.org", now);
    }
    assert!(t.check_at("a@example.org", now).is_err());
    // Spelling doesn't matter, other addresses aren't affected
    assert!(t.check_at(" A@Example.org ", now).is_err());
    assert!(t.check_at("b@example.org", now).is_ok());
    t.success("a@example.org");
    assert!(t.check_at("a@example.org", now).is_ok());
}

#[test]
fn failures_are_forgotten() {
    let t = Throttle::default();
    let now = Instant::now();
    for _ in 0..FREE_ATTEMPTS {
        t.failure_at("a@example.org", now);
    }
    // One more failure long after: counting starts again, no waiting time
    t.failure_at("a@example.org", now + FORGET_AFTER);
    assert!(t.check_at("a@example.org", now + FORGET_AFTER).is_ok());
}

#[test]
fn minutes_round_up() {
    assert_eq!(minutes(Duration::from_secs(1)), 1);
    assert_eq!(minutes(Duration::from_secs(60)), 1);
    assert_eq!(minutes(Duration::from_secs(61)), 2);
}
