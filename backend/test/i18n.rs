use super::*;

#[test]
fn accept_language_picks_preferred_supported_language() {
    assert_eq!(Lang::from_accept_language("de-DE,de;q=0.9,en;q=0.8"), Some(Lang::De));
    assert_eq!(Lang::from_accept_language("en-US,en;q=0.9,de;q=0.8"), Some(Lang::En));
    assert_eq!(Lang::from_accept_language("fr-FR,fr;q=0.9,de;q=0.7,en;q=0.5"), Some(Lang::De));
    assert_eq!(Lang::from_accept_language("en;q=0.5, de"), Some(Lang::De));
    assert_eq!(Lang::from_accept_language("de_AT"), Some(Lang::De));
    assert_eq!(Lang::from_accept_language("de;q=0, en;q=0.1"), Some(Lang::En));
    assert_eq!(Lang::from_accept_language("fr, *"), None);
    assert_eq!(Lang::from_accept_language(""), None);
}

#[test]
fn tr_uses_language_and_fills_placeholders() {
    assert_eq!(tr_in!(Lang::En, "event.unknown_time_zone", tz = "Mars/Base"), "Unknown time zone: Mars/Base");
    assert_eq!(tr_in!(Lang::De, "event.unknown_time_zone", tz = "Mars/Base"), "Unbekannte Zeitzone: Mars/Base");
    assert_eq!(tr_in!(Lang::De, "error.not_found"), "Nicht gefunden");
    // Outside of a request: English
    assert_eq!(tr!("error.not_found"), "Not found");
}

#[tokio::test]
async fn tr_follows_the_request_language() {
    let text = REQUEST_LANG.scope(Some(Lang::De), async { tr!("error.not_found") }).await;
    assert_eq!(text, "Nicht gefunden");
}

type Texts = std::collections::BTreeMap<String, String>;

fn locale(name: &str) -> Texts {
    let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("locales").join(format!("{name}.json"));
    serde_json::from_str(&std::fs::read_to_string(&path).unwrap()).unwrap()
}

/// Placeholders `%{name}` in a text, sorted.
fn placeholders(text: &str) -> Vec<&str> {
    let mut names: Vec<&str> = text.split("%{").skip(1).filter_map(|s| s.split_once('}').map(|(n, _)| n)).collect();
    names.sort();
    names
}

/// Both languages have the same keys with the same placeholders.
#[test]
fn locales_match() {
    let (en, de) = (locale("en"), locale("de"));
    let mut problems = Vec::new();
    for key in en.keys().filter(|k| !de.contains_key(*k)) {
        problems.push(format!("de.json is missing {key}"));
    }
    for key in de.keys().filter(|k| !en.contains_key(*k)) {
        problems.push(format!("en.json is missing {key}"));
    }
    for (key, text) in &en {
        if let Some(other) = de.get(key) {
            if placeholders(text) != placeholders(other) {
                problems.push(format!("{key}: placeholders differ: {text:?} / {other:?}"));
            }
        }
    }
    assert!(problems.is_empty(), "{}", problems.join("\n"));
}

/// Every key used with `tr!` / `tr_in!` exists, and every key in the files is used.
#[test]
fn every_key_exists_and_is_used() {
    fn files(dir: &std::path::Path, out: &mut Vec<std::path::PathBuf>) {
        for entry in std::fs::read_dir(dir).unwrap() {
            let path = entry.unwrap().path();
            if path.is_dir() {
                files(&path, out);
            } else if path.extension().is_some_and(|e| e == "rs") {
                out.push(path);
            }
        }
    }
    let mut paths = Vec::new();
    files(&std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("src"), &mut paths);

    let mut used = std::collections::BTreeSet::new();
    for path in paths {
        let source = std::fs::read_to_string(&path).unwrap();
        for (i, _) in source.match_indices("tr!(").chain(source.match_indices("tr_in!(")) {
            // The first string literal after the macro name (tr_in! has the language before it)
            let rest = &source[i..];
            let Some(start) = rest.find('"') else { continue };
            if rest[..start].contains(';') || rest[..start].contains("$key") {
                continue; // macro definition, not a call
            }
            let literal = &rest[start + 1..];
            used.insert(literal[..literal.find('"').unwrap()].to_string());
        }
    }
    let en = locale("en");
    let missing: Vec<_> = used.iter().filter(|k| !en.contains_key(*k)).collect();
    let unused: Vec<_> = en.keys().filter(|k| !used.contains(*k)).collect();
    assert!(missing.is_empty(), "keys missing in locales/en.json: {missing:?}");
    assert!(unused.is_empty(), "unused keys in locales/en.json: {unused:?}");
}
