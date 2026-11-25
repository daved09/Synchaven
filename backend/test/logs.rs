use super::*;

fn push(buf: &LogBuffer, msg: &str) {
    buf.push(Utc::now(), Level::INFO, "synchaven", msg.to_string());
}

#[test]
fn since_returns_newer_entries() {
    let buf = LogBuffer::default();
    push(&buf, "a");
    push(&buf, "b");
    push(&buf, "c");
    let all = buf.since(0);
    assert_eq!(all.iter().map(|e| e.message.as_str()).collect::<Vec<_>>(), ["a", "b", "c"]);
    let newer = buf.since(all[1].id);
    assert_eq!(newer.len(), 1);
    assert_eq!(newer[0].message, "c");
    assert!(buf.since(all[2].id).is_empty());
}

#[test]
fn keeps_only_the_last_entries() {
    let buf = LogBuffer::default();
    for i in 0..CAPACITY + 5 {
        push(&buf, &i.to_string());
    }
    let all = buf.since(0);
    assert_eq!(all.len(), CAPACITY);
    assert_eq!(all[0].message, "5");
    assert_eq!(all.last().unwrap().id, (CAPACITY + 5) as u64);
}

#[test]
fn clear_keeps_ids_increasing() {
    let buf = LogBuffer::default();
    push(&buf, "a");
    buf.clear();
    assert!(buf.since(0).is_empty());
    push(&buf, "b");
    assert_eq!(buf.since(0)[0].id, 2);
}

#[test]
fn layer_formats_message_and_fields() {
    use tracing_subscriber::layer::SubscriberExt;
    let buf: &'static LogBuffer = Box::leak(Box::default());
    let subscriber = tracing_subscriber::registry().with(buf);
    tracing::subscriber::with_default(subscriber, || {
        tracing::warn!(user = "anna", count = 3, "Hallo {}", "Welt");
        tracing::info!(status = 200);
    });
    let all = buf.since(0);
    assert_eq!(all[0].level, "WARN");
    assert_eq!(all[0].message, "Hallo Welt user=anna count=3");
    assert_eq!(all[1].message, "status=200");
}
