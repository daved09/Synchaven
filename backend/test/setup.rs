use super::*;

#[test]
fn compares_tokens() {
    assert!(same("0123456789abcdef", " 0123456789abcdef\n"));
    assert!(!same("0123456789abcdef", "0123456789abcdeF"));
    assert!(!same("0123456789abcdef", ""));
}

#[tokio::test]
async fn token_file_is_created_checked_and_removed() {
    let dir = std::env::temp_dir().join(format!("synchaven-setup-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir_all(&dir).unwrap();
    let setup = Setup::new(&dir);
    assert!(!setup.check(None).await.unwrap());
    assert!(!setup.check(Some("wrong-token-wrong-token")).await.unwrap());
    let token = std::fs::read_to_string(dir.join(FILE)).unwrap();
    assert!(token.len() >= MIN_LEN);
    assert!(setup.check(Some(&token)).await.unwrap());
    setup.finish().await;
    assert!(!dir.join(FILE).exists());
    std::fs::remove_dir_all(&dir).unwrap();
}
