//! Slows down password guessing for sign-ins (web UI, app and CardDAV).
//!
//! Failed attempts are counted per email address – unknown addresses as well, so a refusal doesn't reveal whether
//! an account exists. The first [FREE_ATTEMPTS] failures cost nothing; after that each attempt has to wait, starting
//! at [BASE_DELAY] and doubling up to [MAX_DELAY]. A correct password resets the count.
//!
//! Counting per account rather than per IP address works behind a reverse proxy (all requests then come from the
//! proxy). The price is that someone can slow down another person's sign-ins – but only new ones: sessions,
//! app passwords and CardDAV sign-ins confirmed recently (see `dav::AuthCache`) are not affected.

use std::{
    collections::HashMap,
    sync::Mutex,
    time::{Duration, Instant},
};

/// Failed attempts without any waiting time
pub const FREE_ATTEMPTS: u32 = 5;
/// Waiting time after the first attempt beyond [FREE_ATTEMPTS]
pub const BASE_DELAY: Duration = Duration::from_secs(30);
/// Longest waiting time between two attempts
pub const MAX_DELAY: Duration = Duration::from_secs(15 * 60);
/// Failures are forgotten after this long without a new attempt
const FORGET_AFTER: Duration = Duration::from_secs(60 * 60);
/// Above this many entries, forgotten ones are removed
const PRUNE_ABOVE: usize = 10_000;

struct Entry {
    failures: u32,
    last: Instant,
}

impl Entry {
    /// Waiting time after the last failure
    fn delay(&self) -> Duration {
        if self.failures < FREE_ATTEMPTS {
            return Duration::ZERO;
        }
        let doublings = (self.failures - FREE_ATTEMPTS).min(16);
        BASE_DELAY.saturating_mul(1 << doublings).min(MAX_DELAY)
    }
}

#[derive(Default)]
pub struct Throttle {
    entries: Mutex<HashMap<String, Entry>>,
}

/// Key of an email address: the same for every spelling the sign-in accepts
fn key(email: &str) -> String {
    email.trim().to_lowercase()
}

impl Throttle {
    /// `Err(remaining)` if the next attempt for `email` has to wait.
    pub fn check(&self, email: &str) -> Result<(), Duration> {
        self.check_at(email, Instant::now())
    }

    fn check_at(&self, email: &str, now: Instant) -> Result<(), Duration> {
        let map = self.entries.lock().unwrap_or_else(|e| e.into_inner());
        let Some(entry) = map.get(&key(email)) else { return Ok(()) };
        let ready = entry.last + entry.delay();
        if now < ready {
            Err(ready - now)
        } else {
            Ok(())
        }
    }

    /// Records a wrong password (or an unknown address).
    pub fn failure(&self, email: &str) {
        self.failure_at(email, Instant::now())
    }

    fn failure_at(&self, email: &str, now: Instant) {
        let mut map = self.entries.lock().unwrap_or_else(|e| e.into_inner());
        if map.len() > PRUNE_ABOVE {
            map.retain(|_, e| now.saturating_duration_since(e.last) < FORGET_AFTER);
        }
        let entry = map.entry(key(email)).or_insert(Entry { failures: 0, last: now });
        if now.saturating_duration_since(entry.last) >= FORGET_AFTER {
            entry.failures = 0;
        }
        entry.failures = entry.failures.saturating_add(1);
        entry.last = now;
    }

    /// A correct password: the count starts again.
    pub fn success(&self, email: &str) {
        self.entries.lock().unwrap_or_else(|e| e.into_inner()).remove(&key(email));
    }
}

/// Whole minutes of a waiting time, rounded up (for messages)
pub fn minutes(wait: Duration) -> u64 {
    wait.as_secs().div_ceil(60).max(1)
}

#[cfg(test)]
#[path = "../test/throttle.rs"]
mod tests;
