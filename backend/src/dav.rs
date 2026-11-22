//! CardDAV-Server (RFC 6352) für die Synchronisierung mit DAVx⁵, Thunderbird, iOS u. a.
//!
//! Anmeldung per HTTP Basic mit E-Mail-Adresse und Passwort des Kontos.
//!
//! ```text
//! /.well-known/carddav                      → Weiterleitung auf /dav/
//! /dav/                                     Einstieg (current-user-principal)
//! /dav/principals/{user}/                   Principal (addressbook-home-set)
//! /dav/addressbooks/{user}/                 Sammlung aller Adressbücher
//! /dav/addressbooks/{user}/{buch}/          Adressbuch
//! /dav/addressbooks/{user}/{buch}/{x}.vcf   Kontakt
//! ```
//!
//! Änderungen erkennen Clients über `getctag` (Änderungszähler je Adressbuch) und `getetag`.

use std::{
    collections::HashMap,
    sync::Mutex,
    time::{Duration, Instant},
};

use axum::{
    body::Body,
    extract::{Request, State},
    http::{header, HeaderMap, HeaderValue, StatusCode},
    response::{IntoResponse, Response},
};
use base64::Engine;
use quick_xml::{events::Event, name::ResolveResult, NsReader};
use sha2::{Digest, Sha256};
use sqlx::FromRow;

use crate::{
    auth::verify_password,
    contacts::{self, AddressBookRow, ContactRow},
    error::{AppError, AppResult},
    models::UserRow,
    time::from_db,
    vcard, AppState,
};

const NS_DAV: &str = "DAV:";
const NS_CARD: &str = "urn:ietf:params:xml:ns:carddav";
const NS_CS: &str = "http://calendarserver.org/ns/";
const MAX_BODY: usize = 10 * 1024 * 1024;
const MAX_VCARD: usize = 5 * 1024 * 1024;

// ---------- Anmeldung ----------

/// Zwischenspeicher erfolgreicher Anmeldungen: Sync-Clients senden bei jeder Anfrage das
/// Passwort mit, Argon2 ist aber absichtlich langsam.
#[derive(Default)]
pub struct AuthCache(Mutex<HashMap<String, Instant>>);

const AUTH_TTL: Duration = Duration::from_secs(15 * 60);

struct DavUser {
    id: String,
    name: String,
}

fn cache_key(email: &str, password: &str, hash: &str) -> String {
    // Der gespeicherte Hash fließt mit ein: Nach einer Passwortänderung passt der Eintrag nicht mehr.
    let digest = Sha256::digest(format!("{email}\0{password}\0{hash}").as_bytes());
    digest.iter().map(|b| format!("{b:02x}")).collect()
}

async fn authenticate(state: &AppState, headers: &HeaderMap) -> AppResult<Option<DavUser>> {
    let Some(value) = headers.get(header::AUTHORIZATION).and_then(|v| v.to_str().ok()) else {
        return Ok(None);
    };
    let Some(b64) = value.strip_prefix("Basic ").or_else(|| value.strip_prefix("basic ")) else {
        return Ok(None);
    };
    let Some(decoded) = base64::engine::general_purpose::STANDARD
        .decode(b64.trim())
        .ok()
        .and_then(|b| String::from_utf8(b).ok())
    else {
        return Ok(None);
    };
    let Some((email, password)) = decoded.split_once(':') else { return Ok(None) };
    let email = email.trim().to_lowercase();
    let Some(user) = sqlx::query_as::<_, UserRow>("SELECT * FROM users WHERE email = ?")
        .bind(&email)
        .fetch_optional(&state.db)
        .await?
    else {
        return Ok(None);
    };

    let key = cache_key(&email, password, &user.password_hash);
    let cached = {
        let map = state.dav_auth.0.lock().unwrap();
        map.get(&key).is_some_and(|exp| *exp > Instant::now())
    };
    if !cached {
        let (password, hash) = (password.to_string(), user.password_hash.clone());
        let ok = tokio::task::spawn_blocking(move || verify_password(&password, &hash)).await.unwrap_or(false);
        if !ok {
            return Ok(None);
        }
        let mut map = state.dav_auth.0.lock().unwrap();
        let now = Instant::now();
        if map.len() > 1000 {
            map.retain(|_, exp| *exp > now);
        }
        map.insert(key, now + AUTH_TTL);
    }
    Ok(Some(DavUser { id: user.id, name: user.name }))
}

fn unauthorized() -> Response {
    (
        StatusCode::UNAUTHORIZED,
        [(header::WWW_AUTHENTICATE, "Basic realm=\"Kalender\", charset=\"UTF-8\"")],
        "Anmeldung erforderlich",
    )
        .into_response()
}

// ---------- XML ----------

#[derive(Debug, Default)]
struct El {
    ns: String,
    name: String,
    children: Vec<El>,
    text: String,
}

impl El {
    fn is(&self, ns: &str, name: &str) -> bool {
        self.ns == ns && self.name == name
    }
    fn child(&self, ns: &str, name: &str) -> Option<&El> {
        self.children.iter().find(|c| c.is(ns, name))
    }
    /// Erstes passendes Element in beliebiger Tiefe.
    fn find(&self, ns: &str, name: &str) -> Option<&El> {
        self.children.iter().find_map(|c| if c.is(ns, name) { Some(c) } else { c.find(ns, name) })
    }
}

fn parse_xml(body: &[u8]) -> Option<El> {
    let text = std::str::from_utf8(body).ok()?;
    let mut reader = NsReader::from_str(text);
    let mut stack = vec![El::default()];
    let element = |ns: ResolveResult, local: &str| El {
        ns: match ns {
            ResolveResult::Bound(n) => n.0.to_string(),
            _ => String::new(),
        },
        name: local.to_string(),
        ..El::default()
    };
    loop {
        match reader.read_resolved_event().ok()? {
            (ns, Event::Start(e)) => stack.push(element(ns, e.local_name().as_ref())),
            (ns, Event::Empty(e)) => {
                let el = element(ns, e.local_name().as_ref());
                stack.last_mut()?.children.push(el);
            }
            (_, Event::End(_)) => {
                let el = stack.pop()?;
                stack.last_mut()?.children.push(el);
            }
            (_, Event::Text(t)) => stack.last_mut()?.text.push_str(&t.xml10_content()),
            (_, Event::CData(t)) => stack.last_mut()?.text.push_str(&t),
            (_, Event::GeneralRef(r)) => {
                let resolved = match r.resolve_char_ref() {
                    Ok(Some(c)) => c.to_string(),
                    _ => quick_xml::escape::resolve_xml_entity(&r).unwrap_or("").to_string(),
                };
                stack.last_mut()?.text.push_str(&resolved);
            }
            (_, Event::Eof) => break,
            _ => {}
        }
    }
    if stack.len() != 1 {
        return None;
    }
    stack.pop()?.children.into_iter().next()
}

fn esc(s: &str) -> String {
    s.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;").replace('"', "&quot;")
}

type QName = (String, String);

fn qname(ns: &str, name: &str) -> QName {
    (ns.to_string(), name.to_string())
}

fn render_prop(q: &QName, inner: Option<&str>) -> String {
    let (open, close) = match q.0.as_str() {
        NS_DAV => (format!("D:{}", q.1), format!("D:{}", q.1)),
        NS_CARD => (format!("C:{}", q.1), format!("C:{}", q.1)),
        NS_CS => (format!("CS:{}", q.1), format!("CS:{}", q.1)),
        ns => (format!("X:{} xmlns:X=\"{}\"", q.1, esc(ns)), format!("X:{}", q.1)),
    };
    match inner {
        Some(i) if !i.is_empty() => format!("<{open}>{i}</{close}>"),
        _ => format!("<{open}/>"),
    }
}

struct Multistatus(String);

impl Multistatus {
    fn new() -> Self {
        Self(String::from(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<D:multistatus xmlns:D=\"DAV:\" xmlns:C=\"urn:ietf:params:xml:ns:carddav\" xmlns:CS=\"http://calendarserver.org/ns/\">",
        ))
    }

    fn propstat(&mut self, href: &str, groups: &[(&str, Vec<String>)]) {
        self.0.push_str(&format!("<D:response><D:href>{}</D:href>", esc(href)));
        for (status, props) in groups.iter().filter(|(_, p)| !p.is_empty()) {
            self.0.push_str("<D:propstat><D:prop>");
            for p in props {
                self.0.push_str(p);
            }
            self.0.push_str(&format!("</D:prop><D:status>HTTP/1.1 {status}</D:status></D:propstat>"));
        }
        self.0.push_str("</D:response>");
    }

    fn status(&mut self, href: &str, status: &str) {
        self.0.push_str(&format!(
            "<D:response><D:href>{}</D:href><D:status>HTTP/1.1 {status}</D:status></D:response>",
            esc(href)
        ));
    }

    fn finish(mut self) -> Response {
        self.0.push_str("</D:multistatus>");
        xml_response(StatusCode::MULTI_STATUS, self.0)
    }
}

fn xml_response(status: StatusCode, body: String) -> Response {
    (status, [(header::CONTENT_TYPE, "application/xml; charset=utf-8")], body).into_response()
}

/// Fehlerantwort mit Vorbedingung, z. B. `<C:no-uid-conflict/>`.
fn precondition(status: StatusCode, condition: &str) -> Response {
    xml_response(
        status,
        format!(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<D:error xmlns:D=\"DAV:\" xmlns:C=\"urn:ietf:params:xml:ns:carddav\">{condition}</D:error>"
        ),
    )
}

fn status(code: StatusCode) -> Response {
    code.into_response()
}

// ---------- Ressourcen ----------

#[derive(Debug, FromRow)]
struct ObjMeta {
    href: String,
    etag: String,
    size: i64,
    updated_at: String,
    vcard: Option<String>,
}

enum Res<'a> {
    Root,
    Principal,
    Home,
    Book(&'a AddressBookRow),
    Object(&'a ObjMeta),
}

struct Ctx<'a> {
    user: &'a DavUser,
}

impl Ctx<'_> {
    fn principal(&self) -> String {
        format!("/dav/principals/{}/", self.user.id)
    }
    fn home(&self) -> String {
        format!("/dav/addressbooks/{}/", self.user.id)
    }
    fn book(&self, id: &str) -> String {
        format!("/dav/addressbooks/{}/{}/", self.user.id, id)
    }

    /// Inhalt einer Eigenschaft oder `None`, wenn es sie für diese Ressource nicht gibt.
    fn prop(&self, res: &Res, q: &QName) -> Option<String> {
        let href = |h: String| format!("<D:href>{}</D:href>", esc(&h));
        let is_collection = !matches!(res, Res::Object(_));
        match (q.0.as_str(), q.1.as_str()) {
            (NS_DAV, "resourcetype") => Some(match res {
                Res::Root | Res::Home => "<D:collection/>".into(),
                Res::Principal => "<D:collection/><D:principal/>".into(),
                Res::Book(_) => "<D:collection/><C:addressbook/>".into(),
                Res::Object(_) => String::new(),
            }),
            (NS_DAV, "current-user-principal") => Some(href(self.principal())),
            (NS_DAV, "principal-URL") if matches!(res, Res::Principal) => Some(href(self.principal())),
            (NS_DAV, "displayname") => match res {
                Res::Root => Some("Kalender".into()),
                Res::Principal => Some(esc(&self.user.name)),
                Res::Home => Some("Adressbücher".into()),
                Res::Book(b) => Some(esc(&b.name)),
                Res::Object(_) => None,
            },
            (NS_CARD, "addressbook-home-set") if matches!(res, Res::Root | Res::Principal) => {
                Some(href(self.home()))
            }
            (NS_DAV, "principal-collection-set") if matches!(res, Res::Root | Res::Principal) => {
                Some(href("/dav/principals/".into()))
            }
            (NS_DAV, "owner") if is_collection => Some(href(self.principal())),
            (NS_DAV, "current-user-privilege-set") => Some(
                ["read", "write", "write-properties", "write-content", "bind", "unbind", "read-current-user-privilege-set"]
                    .iter()
                    .map(|p| format!("<D:privilege><D:{p}/></D:privilege>"))
                    .collect(),
            ),
            (NS_DAV, "supported-report-set") if matches!(res, Res::Book(_)) => Some(
                ["C:addressbook-multiget", "C:addressbook-query"]
                    .iter()
                    .map(|r| format!("<D:supported-report><D:report><{r}/></D:report></D:supported-report>"))
                    .collect(),
            ),
            (NS_CS, "getctag") => match res {
                Res::Book(b) => Some(format!("{}-{}", b.id, b.revision)),
                _ => None,
            },
            (NS_CARD, "addressbook-description") => match res {
                Res::Book(b) => Some(esc(&b.description)),
                _ => None,
            },
            (NS_CARD, "supported-address-data") if matches!(res, Res::Book(_)) => {
                Some("<C:address-data-type content-type=\"text/vcard\" version=\"3.0\"/>".into())
            }
            (NS_CARD, "max-resource-size") if matches!(res, Res::Book(_)) => Some(MAX_VCARD.to_string()),
            (NS_DAV, "getetag") => match res {
                Res::Object(o) => Some(esc(&format!("\"{}\"", o.etag))),
                _ => None,
            },
            (NS_DAV, "getcontenttype") => match res {
                Res::Object(_) => Some("text/vcard; charset=utf-8".into()),
                _ => None,
            },
            (NS_DAV, "getcontentlength") => match res {
                Res::Object(o) => Some(o.size.to_string()),
                _ => None,
            },
            (NS_DAV, "getlastmodified") => match res {
                Res::Object(o) => Some(http_date(&o.updated_at)),
                _ => None,
            },
            (NS_CARD, "address-data") => match res {
                Res::Object(o) => o.vcard.as_deref().map(esc),
                _ => None,
            },
            _ => None,
        }
    }

    fn respond(&self, ms: &mut Multistatus, href: &str, res: &Res, req: &PropReq) {
        let mut found = Vec::new();
        let mut missing = Vec::new();
        match req {
            PropReq::All => {
                for (ns, name) in [
                    (NS_DAV, "resourcetype"),
                    (NS_DAV, "displayname"),
                    (NS_DAV, "getetag"),
                    (NS_DAV, "getcontenttype"),
                    (NS_DAV, "getcontentlength"),
                    (NS_DAV, "getlastmodified"),
                    (NS_CS, "getctag"),
                ] {
                    let q = qname(ns, name);
                    if let Some(v) = self.prop(res, &q) {
                        found.push(render_prop(&q, Some(&v)));
                    }
                }
            }
            PropReq::Props(list) => {
                for q in list {
                    match self.prop(res, q) {
                        Some(v) => found.push(render_prop(q, Some(&v))),
                        None => missing.push(render_prop(q, None)),
                    }
                }
            }
        }
        ms.propstat(href, &[("200 OK", found), ("404 Not Found", missing)]);
    }
}

fn http_date(db: &str) -> String {
    from_db(db).format("%a, %d %b %Y %H:%M:%S GMT").to_string()
}

enum PropReq {
    All,
    Props(Vec<QName>),
}

impl PropReq {
    fn wants_data(&self) -> bool {
        matches!(self, PropReq::Props(p) if p.iter().any(|q| q.0 == NS_CARD && q.1 == "address-data"))
    }

    fn from_prop(el: Option<&El>) -> Self {
        match el {
            Some(p) => PropReq::Props(p.children.iter().map(|c| (c.ns.clone(), c.name.clone())).collect()),
            None => PropReq::All,
        }
    }
}

fn parse_propfind(body: &[u8]) -> Option<PropReq> {
    if body.iter().all(|b| b.is_ascii_whitespace()) {
        return Some(PropReq::All);
    }
    let root = parse_xml(body)?;
    if root.child(NS_DAV, "allprop").is_some() || root.child(NS_DAV, "propname").is_some() {
        return Some(PropReq::All);
    }
    Some(PropReq::from_prop(root.child(NS_DAV, "prop")))
}

// ---------- Pfade ----------

enum Target {
    Root,
    Principal(String),
    Home(String),
    Book(String, String),
    Object(String, String, String),
    Unknown,
}

fn parse_target(path: &str) -> Target {
    let rest = path.strip_prefix("/dav").unwrap_or(path);
    let segs: Vec<&str> = rest.split('/').filter(|s| !s.is_empty()).collect();
    match segs.as_slice() {
        [] => Target::Root,
        ["principals", u] => Target::Principal(u.to_string()),
        ["addressbooks", u] => Target::Home(u.to_string()),
        ["addressbooks", u, b] => Target::Book(u.to_string(), b.to_string()),
        ["addressbooks", u, b, o] => Target::Object(u.to_string(), b.to_string(), o.to_string()),
        _ => Target::Unknown,
    }
}

fn percent_decode(s: &str) -> String {
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        if b[i] == b'%' && i + 2 < b.len() {
            let hex = std::str::from_utf8(&b[i + 1..i + 3]).unwrap_or("");
            if let (2, Ok(v)) = (hex.len(), u8::from_str_radix(hex, 16)) {
                out.push(v);
                i += 3;
                continue;
            }
        }
        out.push(b[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn depth(headers: &HeaderMap) -> u8 {
    match headers.get("depth").and_then(|v| v.to_str().ok()).map(str::trim) {
        Some("0") => 0,
        _ => 1,
    }
}

/// Prüft If-Match / If-None-Match gegen den aktuellen ETag (`None` = Ressource fehlt).
fn preconditions_ok(headers: &HeaderMap, current: Option<&str>) -> bool {
    let list = |name: header::HeaderName| -> Option<Vec<String>> {
        let v = headers.get(name)?.to_str().ok()?;
        Some(v.split(',').map(|t| t.trim().trim_start_matches("W/").trim_matches('"').to_string()).collect())
    };
    if let Some(tags) = list(header::IF_MATCH) {
        let ok = match current {
            Some(cur) => tags.iter().any(|t| t == "*" || t == cur),
            None => false,
        };
        if !ok {
            return false;
        }
    }
    if let Some(tags) = list(header::IF_NONE_MATCH) {
        if let Some(cur) = current {
            if tags.iter().any(|t| t == "*" || t == cur) {
                return false;
            }
        }
    }
    true
}

// ---------- Einstieg ----------

pub async fn well_known() -> Response {
    (StatusCode::MOVED_PERMANENTLY, [(header::LOCATION, "/dav/")]).into_response()
}

pub async fn handle(State(state): State<AppState>, req: Request) -> Response {
    match handle_inner(state, req).await {
        Ok(r) => r,
        Err(e) => e.into_response(),
    }
}

fn options() -> Response {
    let mut r = StatusCode::OK.into_response();
    let h = r.headers_mut();
    h.insert("DAV", HeaderValue::from_static("1, 3, addressbook"));
    h.insert(
        header::ALLOW,
        HeaderValue::from_static("OPTIONS, GET, HEAD, PUT, DELETE, PROPFIND, PROPPATCH, REPORT, MKCOL"),
    );
    r
}

async fn handle_inner(state: AppState, req: Request) -> AppResult<Response> {
    let (parts, body) = req.into_parts();
    let method = parts.method.as_str().to_ascii_uppercase();
    if method == "OPTIONS" {
        return Ok(options());
    }
    let Some(user) = authenticate(&state, &parts.headers).await? else {
        return Ok(unauthorized());
    };
    let body = axum::body::to_bytes(body, MAX_BODY)
        .await
        .map_err(|_| AppError::BadRequest("Anfrage zu groß".into()))?;
    let path = parts.uri.path().to_string();
    let headers = &parts.headers;
    let ctx = Ctx { user: &user };
    let target = parse_target(&path);

    // Nur eigene Ressourcen
    let owner = match &target {
        Target::Principal(u) | Target::Home(u) | Target::Book(u, _) | Target::Object(u, _, _) => Some(u),
        _ => None,
    };
    if owner.is_some_and(|u| *u != user.id) {
        return Ok(status(StatusCode::FORBIDDEN));
    }
    if matches!(target, Target::Unknown) {
        return Ok(status(StatusCode::NOT_FOUND));
    }
    if matches!(target, Target::Home(_) | Target::Root) {
        contacts::ensure_default_book(&state.db, &user.id).await?;
    }

    match (method.as_str(), &target) {
        ("PROPFIND", _) => propfind(&state, &ctx, &target, &path, headers, &body).await,
        ("REPORT", Target::Book(_, b)) => report(&state, &ctx, b, &body).await,
        ("REPORT", _) => Ok(precondition(StatusCode::FORBIDDEN, "<D:supported-report/>")),
        ("PROPPATCH", Target::Book(_, b)) => proppatch(&state, &ctx, b, &body).await,
        ("MKCOL", Target::Book(_, b)) => mkcol(&state, &ctx, b, &body).await,
        ("GET" | "HEAD", Target::Object(_, b, o)) => get_object(&state, &ctx, b, o).await,
        ("GET" | "HEAD", Target::Book(_, b)) => get_book(&state, &ctx, b).await,
        ("GET" | "HEAD", _) => Ok((
            [(header::CONTENT_TYPE, "text/plain; charset=utf-8")],
            "CardDAV-Server von Kalender. In DAVx⁵ diese Adresse mit E-Mail-Adresse und Passwort verwenden.",
        )
            .into_response()),
        ("PUT", Target::Object(_, b, o)) => put_object(&state, &ctx, b, o, headers, &body).await,
        ("DELETE", Target::Object(_, b, o)) => delete_object(&state, &ctx, b, o, headers).await,
        ("DELETE", Target::Book(_, b)) => {
            contacts::owned_book(&state.db, &user.id, b).await?;
            sqlx::query("DELETE FROM addressbooks WHERE id = ?").bind(b).execute(&state.db).await?;
            Ok(status(StatusCode::NO_CONTENT))
        }
        ("PUT" | "DELETE" | "MKCOL" | "PROPPATCH", _) => Ok(status(StatusCode::FORBIDDEN)),
        _ => Ok(status(StatusCode::METHOD_NOT_ALLOWED)),
    }
}

// ---------- PROPFIND ----------

async fn list_objects(state: &AppState, book_id: &str, with_data: bool) -> AppResult<Vec<ObjMeta>> {
    Ok(sqlx::query_as::<_, ObjMeta>(
        "SELECT href, etag, length(CAST(vcard AS BLOB)) AS size, updated_at,
                CASE WHEN ? THEN vcard END AS vcard
         FROM contacts WHERE addressbook_id = ? ORDER BY href",
    )
    .bind(with_data)
    .bind(book_id)
    .fetch_all(&state.db)
    .await?)
}

async fn find_object(state: &AppState, book_id: &str, name: &str, with_data: bool) -> AppResult<Option<ObjMeta>> {
    let decoded = percent_decode(name);
    Ok(sqlx::query_as::<_, ObjMeta>(
        "SELECT href, etag, length(CAST(vcard AS BLOB)) AS size, updated_at,
                CASE WHEN ? THEN vcard END AS vcard
         FROM contacts WHERE addressbook_id = ? AND (href = ? OR href = ?) LIMIT 1",
    )
    .bind(with_data)
    .bind(book_id)
    .bind(name)
    .bind(&decoded)
    .fetch_optional(&state.db)
    .await?)
}

async fn propfind(
    state: &AppState,
    ctx: &Ctx<'_>,
    target: &Target,
    path: &str,
    headers: &HeaderMap,
    body: &[u8],
) -> AppResult<Response> {
    let Some(req) = parse_propfind(body) else {
        return Ok(status(StatusCode::BAD_REQUEST));
    };
    let depth = depth(headers);
    let mut ms = Multistatus::new();
    match target {
        Target::Root => {
            let href = if path.starts_with("/dav") { "/dav/" } else { "/" };
            ctx.respond(&mut ms, href, &Res::Root, &req);
        }
        Target::Principal(_) => ctx.respond(&mut ms, &ctx.principal(), &Res::Principal, &req),
        Target::Home(_) => {
            ctx.respond(&mut ms, &ctx.home(), &Res::Home, &req);
            if depth > 0 {
                for b in contacts::list_books(&state.db, &ctx.user.id).await? {
                    ctx.respond(&mut ms, &ctx.book(&b.id), &Res::Book(&b), &req);
                }
            }
        }
        Target::Book(_, id) => {
            let book = contacts::owned_book(&state.db, &ctx.user.id, id).await?;
            let base = ctx.book(&book.id);
            ctx.respond(&mut ms, &base, &Res::Book(&book), &req);
            if depth > 0 {
                for o in list_objects(state, &book.id, req.wants_data()).await? {
                    ctx.respond(&mut ms, &format!("{base}{}", o.href), &Res::Object(&o), &req);
                }
            }
        }
        Target::Object(_, b, name) => {
            let book = contacts::owned_book(&state.db, &ctx.user.id, b).await?;
            let Some(o) = find_object(state, &book.id, name, req.wants_data()).await? else {
                return Ok(status(StatusCode::NOT_FOUND));
            };
            ctx.respond(&mut ms, &format!("{}{}", ctx.book(&book.id), o.href), &Res::Object(&o), &req);
        }
        Target::Unknown => return Ok(status(StatusCode::NOT_FOUND)),
    }
    Ok(ms.finish())
}

// ---------- REPORT ----------

async fn report(state: &AppState, ctx: &Ctx<'_>, book_id: &str, body: &[u8]) -> AppResult<Response> {
    let book = contacts::owned_book(&state.db, &ctx.user.id, book_id).await?;
    let base = ctx.book(&book.id);
    let Some(root) = parse_xml(body) else {
        return Ok(status(StatusCode::BAD_REQUEST));
    };
    let req = PropReq::from_prop(root.child(NS_DAV, "prop"));
    let mut ms = Multistatus::new();

    if root.is(NS_CARD, "addressbook-multiget") {
        for h in root.children.iter().filter(|c| c.is(NS_DAV, "href")) {
            let href = h.text.trim();
            // Absolute URLs auf den Pfad kürzen
            let path = match href.find("://") {
                Some(i) => href[i + 3..].find('/').map(|j| &href[i + 3 + j..]).unwrap_or("/"),
                None => href,
            };
            let name = path.rsplit('/').next().unwrap_or("");
            let in_book = percent_decode(path).starts_with(&base) || path.starts_with(&base);
            match (in_book && !name.is_empty()).then_some(()) {
                Some(()) => match find_object(state, &book.id, name, req.wants_data()).await? {
                    Some(o) => ctx.respond(&mut ms, &format!("{base}{}", o.href), &Res::Object(&o), &req),
                    None => ms.status(href, "404 Not Found"),
                },
                None => ms.status(href, "404 Not Found"),
            }
        }
        return Ok(ms.finish());
    }

    if root.is(NS_CARD, "addressbook-query") {
        // Filter werden nicht ausgewertet: Es werden alle Kontakte geliefert.
        for o in list_objects(state, &book.id, req.wants_data()).await? {
            ctx.respond(&mut ms, &format!("{base}{}", o.href), &Res::Object(&o), &req);
        }
        return Ok(ms.finish());
    }

    Ok(precondition(StatusCode::FORBIDDEN, "<D:supported-report/>"))
}

// ---------- Schreibende Methoden ----------

/// Neue Werte für Anzeigename/Beschreibung aus PROPPATCH bzw. erweitertem MKCOL.
struct BookProps {
    name: Option<String>,
    description: Option<String>,
    rejected: Vec<QName>,
    accepted: Vec<QName>,
}

fn book_props(root: &El) -> BookProps {
    let mut p = BookProps { name: None, description: None, rejected: vec![], accepted: vec![] };
    for action in root.children.iter().filter(|c| c.ns == NS_DAV && (c.name == "set" || c.name == "remove")) {
        let set = action.name == "set";
        let Some(prop) = action.child(NS_DAV, "prop") else { continue };
        for el in &prop.children {
            let q = (el.ns.clone(), el.name.clone());
            let value = if set { el.text.trim().to_string() } else { String::new() };
            match (el.ns.as_str(), el.name.as_str()) {
                (NS_DAV, "displayname") => p.name = Some(value),
                (NS_CARD, "addressbook-description") => p.description = Some(value),
                // Beim Anlegen per MKCOL gehört der Ressourcentyp dazu
                (NS_DAV, "resourcetype") if el.child(NS_CARD, "addressbook").is_some() => {}
                // Farben u. Ä. werden stillschweigend akzeptiert, aber nicht gespeichert
                (ns, _) if ns != NS_DAV && ns != NS_CARD => {}
                _ => {
                    p.rejected.push(q);
                    continue;
                }
            }
            p.accepted.push((el.ns.clone(), el.name.clone()));
        }
    }
    p
}

async fn proppatch(state: &AppState, ctx: &Ctx<'_>, book_id: &str, body: &[u8]) -> AppResult<Response> {
    let book = contacts::owned_book(&state.db, &ctx.user.id, book_id).await?;
    let Some(root) = parse_xml(body).filter(|r| r.is(NS_DAV, "propertyupdate")) else {
        return Ok(status(StatusCode::BAD_REQUEST));
    };
    let p = book_props(&root);
    let render = |list: &[QName]| list.iter().map(|q| render_prop(q, None)).collect::<Vec<_>>();
    let mut ms = Multistatus::new();
    let href = ctx.book(&book.id);
    if !p.rejected.is_empty() {
        ms.propstat(&href, &[("403 Forbidden", render(&p.rejected)), ("424 Failed Dependency", render(&p.accepted))]);
        return Ok(ms.finish());
    }
    let name = p.name.filter(|n| !n.is_empty()).map(|n| n.chars().take(100).collect()).unwrap_or(book.name);
    let description = p.description.unwrap_or(book.description);
    sqlx::query("UPDATE addressbooks SET name = ?, description = ?, revision = revision + 1, updated_at = ? WHERE id = ?")
        .bind(&name)
        .bind(&description)
        .bind(crate::time::to_db(crate::time::now()))
        .bind(&book.id)
        .execute(&state.db)
        .await?;
    ms.propstat(&href, &[("200 OK", render(&p.accepted))]);
    Ok(ms.finish())
}

async fn mkcol(state: &AppState, ctx: &Ctx<'_>, id: &str, body: &[u8]) -> AppResult<Response> {
    let valid = !id.is_empty() && id.len() <= 100 && id.chars().all(|c| c.is_ascii_alphanumeric() || "-_".contains(c));
    if !valid {
        return Ok(status(StatusCode::FORBIDDEN));
    }
    let exists: Option<(String,)> = sqlx::query_as("SELECT id FROM addressbooks WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await?;
    if exists.is_some() {
        return Ok(status(StatusCode::METHOD_NOT_ALLOWED));
    }
    let mut name = "Adressbuch".to_string();
    let mut description = String::new();
    if !body.iter().all(|b| b.is_ascii_whitespace()) {
        let Some(root) = parse_xml(body) else {
            return Ok(status(StatusCode::BAD_REQUEST));
        };
        // Nur Adressbücher, keine anderen Sammlungstypen (z. B. Kalender)
        if let Some(rt) = root.find(NS_DAV, "resourcetype") {
            if rt.child(NS_CARD, "addressbook").is_none() {
                return Ok(precondition(StatusCode::FORBIDDEN, "<D:valid-resourcetype/>"));
            }
        }
        let p = book_props(&root);
        if let Some(n) = p.name.filter(|n| !n.is_empty()) {
            name = n.chars().take(100).collect();
        }
        description = p.description.unwrap_or_default();
    }
    contacts::create_book(&state.db, &ctx.user.id, id, &name, &description).await?;
    Ok(status(StatusCode::CREATED))
}

async fn find_row(state: &AppState, book_id: &str, name: &str) -> AppResult<Option<ContactRow>> {
    Ok(sqlx::query_as::<_, ContactRow>(
        "SELECT * FROM contacts WHERE addressbook_id = ? AND (href = ? OR href = ?) LIMIT 1",
    )
    .bind(book_id)
    .bind(name)
    .bind(percent_decode(name))
    .fetch_optional(&state.db)
    .await?)
}

fn etag_header(etag: &str) -> (header::HeaderName, String) {
    (header::ETAG, format!("\"{etag}\""))
}

async fn get_object(state: &AppState, ctx: &Ctx<'_>, book_id: &str, name: &str) -> AppResult<Response> {
    let book = contacts::owned_book(&state.db, &ctx.user.id, book_id).await?;
    let Some(row) = find_row(state, &book.id, name).await? else {
        return Ok(status(StatusCode::NOT_FOUND));
    };
    Ok((
        [
            (header::CONTENT_TYPE, "text/vcard; charset=utf-8".to_string()),
            etag_header(&row.etag),
            (header::LAST_MODIFIED, http_date(&row.updated_at)),
        ],
        row.vcard,
    )
        .into_response())
}

async fn get_book(state: &AppState, ctx: &Ctx<'_>, book_id: &str) -> AppResult<Response> {
    let book = contacts::owned_book(&state.db, &ctx.user.id, book_id).await?;
    let mut body = String::new();
    for o in list_objects(state, &book.id, true).await? {
        body.push_str(o.vcard.unwrap_or_default().trim_end());
        body.push_str("\r\n");
    }
    Ok(([(header::CONTENT_TYPE, "text/vcard; charset=utf-8")], body).into_response())
}

async fn put_object(
    state: &AppState,
    ctx: &Ctx<'_>,
    book_id: &str,
    name: &str,
    headers: &HeaderMap,
    body: &[u8],
) -> AppResult<Response> {
    let book = contacts::owned_book(&state.db, &ctx.user.id, book_id).await?;
    let existing = find_row(state, &book.id, name).await?;
    if !preconditions_ok(headers, existing.as_ref().map(|r| r.etag.as_str())) {
        return Ok(status(StatusCode::PRECONDITION_FAILED));
    }
    if body.len() > MAX_VCARD {
        return Ok(precondition(StatusCode::FORBIDDEN, "<C:max-resource-size/>"));
    }
    let Ok(text) = std::str::from_utf8(body) else {
        return Ok(precondition(StatusCode::FORBIDDEN, "<C:supported-address-data/>"));
    };
    let mut cards = vcard::parse_cards(text);
    if cards.len() != 1 {
        return Ok(precondition(StatusCode::FORBIDDEN, "<C:valid-address-data/>"));
    }
    let card = cards.remove(0);
    let stem = percent_decode(name);
    let stem = stem.strip_suffix(".vcf").unwrap_or(&stem);
    let idx = contacts::index(&card, stem);

    let mut tx = state.db.begin().await?;
    if let Some(other) = contacts::find_by_uid(&mut tx, &book.id, &idx.uid).await? {
        if existing.as_ref().is_none_or(|e| e.id != other.id) {
            return Ok(precondition(
                StatusCode::CONFLICT,
                &format!("<C:no-uid-conflict><D:href>{}{}</D:href></C:no-uid-conflict>", esc(&ctx.book(&book.id)), esc(&other.href)),
            ));
        }
    }
    let created = existing.is_none();
    match &existing {
        Some(row) => contacts::update(&mut tx, row, text, &idx).await?,
        None => {
            contacts::insert(&mut tx, &book.id, name, text, &idx).await?;
        }
    }
    tx.commit().await?;
    let code = if created { StatusCode::CREATED } else { StatusCode::NO_CONTENT };
    Ok((code, [etag_header(&contacts::etag_of(text))], Body::empty()).into_response())
}

async fn delete_object(
    state: &AppState,
    ctx: &Ctx<'_>,
    book_id: &str,
    name: &str,
    headers: &HeaderMap,
) -> AppResult<Response> {
    let book = contacts::owned_book(&state.db, &ctx.user.id, book_id).await?;
    let Some(row) = find_row(state, &book.id, name).await? else {
        return Ok(status(StatusCode::NOT_FOUND));
    };
    if !preconditions_ok(headers, Some(&row.etag)) {
        return Ok(status(StatusCode::PRECONDITION_FAILED));
    }
    let mut conn = state.db.acquire().await?;
    contacts::delete(&mut conn, &row).await?;
    Ok(status(StatusCode::NO_CONTENT))
}

#[cfg(test)]
mod tests {
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
}
