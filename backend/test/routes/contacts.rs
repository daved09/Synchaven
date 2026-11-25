use super::*;

#[test]
fn missing_categories_keep_old_ones() {
    let old = vec!["Familie".to_string()];
    let input: ContactInput = serde_json::from_str(r#"{"addressbook_id":"b","given_name":"A"}"#).unwrap();
    assert_eq!(input.data(&old).categories, old);
    let input: ContactInput =
        serde_json::from_str(r#"{"addressbook_id":"b","given_name":"A","categories":["Arbeit"]}"#).unwrap();
    let data = input.data(&old);
    assert_eq!((data.categories, data.given_name.as_str()), (vec!["Arbeit".to_string()], "A"));
    let input: ContactInput = serde_json::from_str(r#"{"addressbook_id":"b","categories":[]}"#).unwrap();
    assert!(input.data(&old).categories.is_empty());
}
