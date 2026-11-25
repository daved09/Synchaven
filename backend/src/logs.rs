//! Recent log output kept in memory for the admin page.
//!
//! [`LogBuffer`] is a `tracing` layer next to the console output: it sees the same events (after the
//! `RUST_LOG` filter) and keeps the last [`CAPACITY`] of them. Nothing is written to disk; after a
//! restart the buffer starts empty again.

use std::{collections::VecDeque, fmt::Write as _, sync::Mutex};

use chrono::{DateTime, Utc};
use serde::Serialize;
use tracing::{
    field::{Field, Visit},
    Event, Level, Subscriber,
};
use tracing_subscriber::{layer::Context, Layer};

/// Number of entries kept
pub const CAPACITY: usize = 2000;

#[derive(Debug, Clone, Serialize)]
pub struct LogEntry {
    /// Increasing number, for fetching only newer entries (`after`)
    pub id: u64,
    pub time: DateTime<Utc>,
    /// `ERROR`, `WARN`, `INFO`, `DEBUG` or `TRACE`
    pub level: &'static str,
    /// Module that logged the entry, e.g. `synchaven::routes::admin`
    pub target: String,
    /// Message followed by the other fields as `key=value`
    pub message: String,
}

#[derive(Default)]
pub struct LogBuffer {
    inner: Mutex<Inner>,
}

#[derive(Default)]
struct Inner {
    entries: VecDeque<LogEntry>,
    next_id: u64,
}

impl LogBuffer {
    pub fn push(&self, time: DateTime<Utc>, level: Level, target: &str, message: String) {
        let mut inner = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        inner.next_id += 1;
        let entry = LogEntry { id: inner.next_id, time, level: level.as_str(), target: target.to_string(), message };
        if inner.entries.len() == CAPACITY {
            inner.entries.pop_front();
        }
        inner.entries.push_back(entry);
    }

    /// Entries with an `id` greater than `after`, oldest first
    pub fn since(&self, after: u64) -> Vec<LogEntry> {
        let inner = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        // Ids are ascending: skip the older ones from the front
        let start = inner.entries.partition_point(|e| e.id <= after);
        inner.entries.range(start..).cloned().collect()
    }

    pub fn clear(&self) {
        self.inner.lock().unwrap_or_else(|e| e.into_inner()).entries.clear();
    }
}

/// Collects `message` and the other fields of an event into one line
#[derive(Default)]
struct MessageVisitor {
    message: String,
    fields: String,
}

impl Visit for MessageVisitor {
    fn record_str(&mut self, field: &Field, value: &str) {
        if field.name() == "message" {
            self.message.push_str(value);
        } else {
            let _ = write!(self.fields, " {}={value}", field.name());
        }
    }

    fn record_debug(&mut self, field: &Field, value: &dyn std::fmt::Debug) {
        if field.name() == "message" {
            let _ = write!(self.message, "{value:?}");
        } else {
            let _ = write!(self.fields, " {}={value:?}", field.name());
        }
    }
}

impl<S: Subscriber> Layer<S> for &'static LogBuffer {
    fn on_event(&self, event: &Event<'_>, _ctx: Context<'_, S>) {
        let mut visitor = MessageVisitor::default();
        event.record(&mut visitor);
        let mut message = visitor.message;
        if message.is_empty() {
            message = visitor.fields.trim_start().to_string();
        } else {
            message.push_str(&visitor.fields);
        }
        let meta = event.metadata();
        self.push(Utc::now(), *meta.level(), meta.target(), message);
    }
}

#[cfg(test)]
#[path = "../test/logs.rs"]
mod tests;
