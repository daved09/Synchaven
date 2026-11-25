use super::*;

#[test]
fn parses_propfind() {
    let body = br#"<?xml version="1.0"?><d:propfind xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/"><d:prop><d:getetag/><cs:getctag/><card:address-data xmlns:card="urn:ietf:params:xml:ns:carddav"/></d:prop></d:propfind>"#;
    let PropReq::Props(p) = parse_propfind(body).unwrap() else { panic!() };
    assert_eq!(p, vec![qname(NS_DAV, "getetag"), qname(NS_CS, "getctag"), qname(NS_CARD, "address-data")]);
}

#[test]
fn parses_multiget_with_entities() {
    let body = br#"<C:addressbook-multiget xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:carddav"><D:prop><D:getetag/></D:prop><D:href>/dav/a/b&amp;c.vcf</D:href><D:href>/x%20y.vcf</D:href></C:addressbook-multiget>"#;
    let root = parse_xml(body).unwrap();
    assert!(root.is(NS_CARD, "addressbook-multiget"));
    let hrefs: Vec<_> = root.children.iter().filter(|c| c.is(NS_DAV, "href")).map(|c| c.text.as_str()).collect();
    assert_eq!(hrefs, vec!["/dav/a/b&c.vcf", "/x%20y.vcf"]);
    assert_eq!(percent_decode("x%20y%C3%A4.vcf"), "x yä.vcf");
    assert_eq!(percent_decode("100%"), "100%");
}

#[test]
fn etag_preconditions() {
    let mut h = HeaderMap::new();
    assert!(preconditions_ok(&h, None));
    h.insert(header::IF_NONE_MATCH, HeaderValue::from_static("*"));
    assert!(preconditions_ok(&h, None));
    assert!(!preconditions_ok(&h, Some("abc")));
    let mut h = HeaderMap::new();
    h.insert(header::IF_MATCH, HeaderValue::from_static("\"abc\""));
    assert!(preconditions_ok(&h, Some("abc")));
    assert!(!preconditions_ok(&h, Some("def")));
    assert!(!preconditions_ok(&h, None));
}
