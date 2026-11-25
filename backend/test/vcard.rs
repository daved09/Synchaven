use super::*;

const V3: &str = "BEGIN:VCARD\r\nVERSION:3.0\r\nPRODID:-//DAVx5\r\nUID:abc-1\r\nFN:Erika Mustermann\r\nN:Mustermann;Erika;;Dr.;\r\nORG:Firma\\, GmbH;Vertrieb\r\nitem1.TEL:+49 30 1234\r\nitem1.X-ABLabel:Zentrale\r\nTEL;TYPE=CELL:0171 555\r\nEMAIL;TYPE=WORK:erika@example.org\r\nADR;TYPE=HOME:;;Heidestr. 17;Köln;;51147;Deutschland\r\nBDAY:1964-08-12\r\nNOTE:Zeile 1\\nZeile 2\r\nPHOTO;ENCODING=b;TYPE=JPEG:/9j/4AAQ\r\n SkZJRg==\r\nX-CUSTOM:bleibt\r\nEND:VCARD\r\n";

#[test]
fn reads_v3() {
    let cards = parse_cards(V3);
    assert_eq!(cards.len(), 1);
    let d = extract(&cards[0]);
    assert_eq!(d.full_name, "Dr. Erika Mustermann");
    assert_eq!(d.family_name, "Mustermann");
    assert_eq!(d.prefix, "Dr.");
    assert_eq!(d.organization, "Firma, GmbH");
    assert_eq!(d.department, "Vertrieb");
    assert_eq!(d.phones[0], TypedValue { kind: "Zentrale".into(), value: "+49 30 1234".into() });
    assert_eq!(d.phones[1].kind, "mobile");
    assert_eq!(d.emails[0].kind, "work");
    assert_eq!(d.addresses[0].locality, "Köln");
    assert_eq!(d.birthday, "1964-08-12");
    assert_eq!(d.note, "Zeile 1\nZeile 2");
    assert_eq!(photo(&cards[0]).unwrap(), "data:image/jpeg;base64,/9j/4AAQSkZJRg==");
}

#[test]
fn edit_keeps_unknown_fields() {
    let mut card = parse_cards(V3).remove(0);
    let old = extract(&card);
    let mut new = old.clone();
    new.phones.remove(1);
    new.phones.push(TypedValue { kind: "work".into(), value: "089 999".into() });
    new.note = "Neu; mit Semikolon".into();
    apply(&mut card, &old, &new);
    let text = card.serialize();
    assert!(text.contains("X-CUSTOM:bleibt"));
    assert!(text.contains("item1.TEL:+49 30 1234"));
    assert!(text.contains("item1.X-ABLABEL:Zentrale"));
    assert!(text.contains("TEL;TYPE=WORK:089 999"));
    assert!(!text.contains("0171"));
    assert!(text.contains("PHOTO;ENCODING=b;TYPE=JPEG:/9j/4AAQSkZJRg=="));
    let back = extract(&parse_cards(&text)[0]);
    assert_eq!(back, new);
}

#[test]
fn photo_set_and_remove() {
    let mut card = parse_cards(V3).remove(0);
    assert!(set_photo(&mut card, "data:image/png;base64,iVBO Rw0K"));
    let text = card.serialize();
    assert!(text.contains("PHOTO;ENCODING=b;TYPE=PNG:iVBORw0K"), "{text}");
    assert_eq!(text.matches("PHOTO").count(), 1);
    assert_eq!(photo(&parse_cards(&text)[0]).unwrap(), "data:image/png;base64,iVBORw0K");
    assert!(!set_photo(&mut card, "https://example.org/a.jpg"));
    assert!(set_photo(&mut card, ""));
    assert!(photo(&card).is_none());
    assert!(card.serialize().contains("X-CUSTOM:bleibt"));
}

#[test]
fn custom_label_and_removal() {
    let mut card = parse_cards(V3).remove(0);
    let old = extract(&card);
    let mut new = old.clone();
    new.phones = vec![TypedValue { kind: "Ferienhaus".into(), value: "040 1".into() }];
    apply(&mut card, &old, &new);
    let text = card.serialize();
    assert!(!text.contains("Zentrale"), "verwaiste Bezeichnung muss entfernt werden: {text}");
    assert_eq!(extract(&parse_cards(&text)[0]).phones, new.phones);
}

#[test]
fn imports_v21() {
    let v21 = "BEGIN:VCARD\r\nVERSION:2.1\r\nN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:M=C3=BCller;J=C3=BCrgen;;;\r\nFN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:J=C3=BCrgen M=C3=BCller, Sen.\r\nTEL;CELL;PREF:+491701234\r\nTEL;HOME:030 12\r\nNOTE;ENCODING=QUOTED-PRINTABLE:Sehr lange Notiz, die umbro=\r\nchen wird=0D=0Aneue Zeile\r\nEND:VCARD\r\n";
    let card = normalize_import(parse_cards(v21).remove(0), || "neu".into());
    let d = extract(&card);
    assert_eq!(card.version(), "3.0");
    assert_eq!(card.uid().as_deref(), Some("neu"));
    assert_eq!(d.family_name, "Müller");
    assert_eq!(d.full_name, "Jürgen Müller");
    assert_eq!(d.phones[0], TypedValue { kind: "mobile".into(), value: "+491701234".into() });
    assert_eq!(d.phones[1].kind, "home");
    assert_eq!(d.note, "Sehr lange Notiz, die umbrochen wird\r\nneue Zeile".replace("\r\n", "\n"));
    let text = card.serialize();
    assert!(text.contains("FN:Jürgen Müller\\, Sen."));
    assert!(text.contains("TEL;TYPE=CELL,PREF:+491701234"));
}

#[test]
fn create_roundtrip() {
    let data = ContactData {
        given_name: "Max".into(),
        family_name: "Mustermann".into(),
        emails: vec![TypedValue { kind: "home".into(), value: "max@example.org".into() }],
        birthday: "--03-04".into(),
        addresses: vec![Address { kind: "work".into(), street: "Weg 1".into(), ..Default::default() }],
        ..Default::default()
    };
    let mut d = data.clone();
    d.normalize();
    assert_eq!(d.full_name, "Max Mustermann");
    let card = create("u1", &d, Utc::now());
    let back = extract(&parse_cards(&card.serialize())[0]);
    assert_eq!(back, d);
    assert!(card.serialize().contains("BDAY;X-APPLE-OMIT-YEAR=1604:1604-03-04"));
    assert!(card.serialize().lines().all(|l| l.len() <= 76));
}

#[test]
fn full_name_follows_name_parts() {
    // Outdated FN (renamed in a program that doesn't update FN)
    let mut card = parse_cards("BEGIN:VCARD\nVERSION:3.0\nFN:Heike Weiss\nN:Heike;Tante;;;\nEND:VCARD\n").remove(0);
    let old = extract(&card);
    assert_eq!(old.full_name, "Tante Heike");
    let mut new = old.clone();
    new.note = "x".into();
    apply(&mut card, &old, &new);
    assert!(card.serialize().contains("FN:Tante Heike"));

    // Incoming display name is ignored when name parts exist
    let mut d = ContactData { full_name: "Alt".into(), given_name: "Neu".into(), ..Default::default() };
    d.normalize();
    assert_eq!(d.full_name, "Neu");

    // Without name parts, FN is kept
    let card = parse_cards("BEGIN:VCARD\nVERSION:3.0\nFN:Bäckerei Schmidt\nEND:VCARD\n").remove(0);
    assert_eq!(extract(&card).full_name, "Bäckerei Schmidt");
}

#[test]
fn nickname_is_display_name() {
    let mut d = ContactData { given_name: "Johannes".into(), family_name: "Müller".into(), nickname: " Hannes ".into(), ..Default::default() };
    d.normalize();
    assert_eq!(d.full_name, "Hannes");

    // Also without name parts and against an existing FN
    let card = parse_cards("BEGIN:VCARD\nVERSION:3.0\nFN:Alt\nNICKNAME:Hannes\nEND:VCARD\n").remove(0);
    assert_eq!(extract(&card).full_name, "Hannes");

    // Nickname removed: back to the name parts
    d.nickname.clear();
    d.normalize();
    assert_eq!(d.full_name, "Johannes Müller");
}

#[test]
fn categories() {
    let mut card =
        parse_cards("BEGIN:VCARD\nVERSION:3.0\nFN:A\nCATEGORIES:Familie,Freunde\\, alt\nCATEGORIES:familie,Arbeit\nEND:VCARD\n")
            .remove(0);
    let old = extract(&card);
    assert_eq!(old.categories, ["Familie", "Freunde, alt", "Arbeit"]);
    assert!(search_text(&old).contains("arbeit"));

    let mut new = old.clone();
    new.categories = vec![" Arbeit ".into(), "Verein".into(), "verein".into(), "".into()];
    new.normalize();
    assert_eq!(new.categories, ["Arbeit", "Verein"]);
    apply(&mut card, &old, &new);
    let text = card.serialize();
    assert_eq!(text.matches("CATEGORIES").count(), 1);
    assert!(text.contains("CATEGORIES:Arbeit,Verein"));

    let old = extract(&card);
    let new = ContactData { categories: vec![], ..old.clone() };
    apply(&mut card, &old, &new);
    assert!(!card.serialize().contains("CATEGORIES"));

    // vCard 2.1: commas separate the categories
    let v21 = parse_cards("BEGIN:VCARD\nVERSION:2.1\nN:A;B\nCATEGORIES:Familie,Arbeit\nEND:VCARD\n").remove(0);
    assert_eq!(extract(&normalize_import(v21, || "x".into())).categories, ["Familie", "Arbeit"]);
}

#[test]
fn apple_birthday_without_year() {
    let card = parse_cards("BEGIN:VCARD\nVERSION:3.0\nFN:A\nBDAY;X-APPLE-OMIT-YEAR=1604:1604-05-12\nEND:VCARD\n").remove(0);
    assert_eq!(extract(&card).birthday, "--05-12");
}
