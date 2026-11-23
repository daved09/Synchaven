//! vCard lesen und schreiben (RFC 2426 / RFC 6350), inkl. Import von vCard 2.1.
//!
//! Kontakte werden als vollständiger vCard-Text gespeichert. Änderungen aus der Weboberfläche
//! ersetzen nur die Eigenschaften, die sie tatsächlich ändern – alles andere (Fotos, Gruppen,
//! Felder anderer Programme) bleibt unverändert erhalten, damit DAVx⁵ & Co. nichts verlieren.

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

// ---------- Zeilen ----------

#[derive(Debug, Clone, PartialEq)]
pub struct Line {
    pub group: Option<String>,
    /// Großgeschrieben, z. B. `TEL`
    pub name: String,
    /// Parametername großgeschrieben, Wert ohne Anführungszeichen
    pub params: Vec<(String, String)>,
    /// Roher (escapter) Wert
    pub value: String,
}

impl Line {
    pub fn new(name: &str, value: impl Into<String>) -> Self {
        Self { group: None, name: name.to_string(), params: vec![], value: value.into() }
    }

    fn with_types(mut self, types: &[&str]) -> Self {
        if !types.is_empty() {
            self.params.push(("TYPE".into(), types.join(",")));
        }
        self
    }

    pub fn param(&self, key: &str) -> Option<&str> {
        self.params.iter().find(|(k, _)| k == key).map(|(_, v)| v.as_str())
    }

    /// Alle TYPE-Werte, kleingeschrieben (`TYPE=work,voice;TYPE=pref` → work, voice, pref).
    pub fn types(&self) -> Vec<String> {
        self.params
            .iter()
            .filter(|(k, _)| k == "TYPE")
            .flat_map(|(_, v)| v.split(','))
            .map(|t| t.trim().trim_matches('"').to_lowercase())
            .filter(|t| !t.is_empty())
            .collect()
    }

    fn serialize(&self, out: &mut String) {
        let mut s = String::new();
        if let Some(g) = &self.group {
            s.push_str(g);
            s.push('.');
        }
        s.push_str(&self.name);
        for (k, v) in &self.params {
            s.push(';');
            s.push_str(k);
            s.push('=');
            if v.contains([':', ';']) {
                s.push('"');
                s.push_str(&v.replace('"', "'"));
                s.push('"');
            } else {
                s.push_str(v);
            }
        }
        s.push(':');
        s.push_str(&self.value);
        fold(&s, out);
    }
}

/// Zeilen nach 75 Oktetts umbrechen, ohne UTF-8-Zeichen zu zerteilen.
fn fold(line: &str, out: &mut String) {
    let mut len = 0;
    for ch in line.chars() {
        let w = ch.len_utf8();
        if len + w > 75 {
            out.push_str("\r\n ");
            len = 1;
        }
        out.push(ch);
        len += w;
    }
    out.push_str("\r\n");
}

fn is_qp(head: &str) -> bool {
    head.to_ascii_uppercase().contains("QUOTED-PRINTABLE")
}

/// Fortsetzungszeilen zusammenführen. vCard 2.1 bricht Quoted-Printable-Werte zusätzlich
/// mit `=` am Zeilenende um („soft line break“), die Folgezeile beginnt dann ohne Leerzeichen.
fn unfold(text: &str) -> Vec<String> {
    let text = text.trim_start_matches('\u{feff}');
    let raw: Vec<&str> = text.split('\n').map(|l| l.strip_suffix('\r').unwrap_or(l)).collect();
    let mut lines: Vec<String> = Vec::new();
    let mut i = 0;
    while i < raw.len() {
        let line = raw[i];
        i += 1;
        if (line.starts_with(' ') || line.starts_with('\t')) && !lines.is_empty() {
            lines.last_mut().unwrap().push_str(&line[1..]);
            continue;
        }
        if line.trim().is_empty() {
            continue;
        }
        let mut cur = line.to_string();
        let head_is_qp = cur.split_once(':').is_some_and(|(h, _)| is_qp(h));
        while head_is_qp && cur.ends_with('=') && i < raw.len() {
            cur.pop();
            cur.push_str(raw[i]);
            i += 1;
        }
        lines.push(cur);
    }
    lines
}

/// Teilt an `sep`, ignoriert dabei Trennzeichen innerhalb von Anführungszeichen.
fn split_unquoted(s: &str, sep: char) -> Vec<&str> {
    let mut parts = Vec::new();
    let mut in_quotes = false;
    let mut last = 0;
    for (i, c) in s.char_indices() {
        if c == '"' {
            in_quotes = !in_quotes;
        } else if c == sep && !in_quotes {
            parts.push(&s[last..i]);
            last = i + c.len_utf8();
        }
    }
    parts.push(&s[last..]);
    parts
}

fn parse_line(line: &str) -> Option<Line> {
    let mut in_quotes = false;
    let colon = line.char_indices().find_map(|(i, c)| {
        if c == '"' {
            in_quotes = !in_quotes;
        }
        (c == ':' && !in_quotes).then_some(i)
    })?;
    let (head, value) = (&line[..colon], &line[colon + 1..]);
    let mut segs = split_unquoted(head, ';').into_iter();
    let full_name = segs.next()?.trim();
    let (group, name) = match full_name.rsplit_once('.') {
        Some((g, n)) => (Some(g.to_string()), n),
        None => (None, full_name),
    };
    if name.is_empty() {
        return None;
    }
    let params = segs
        .filter(|p| !p.trim().is_empty())
        .map(|p| match p.split_once('=') {
            Some((k, v)) => (k.trim().to_uppercase(), v.trim().trim_matches('"').to_string()),
            // vCard 2.1: Parameter ohne Namen, z. B. `TEL;CELL;PREF:` oder `;QUOTED-PRINTABLE`
            None => {
                let v = p.trim().to_uppercase();
                let key = match v.as_str() {
                    "QUOTED-PRINTABLE" | "BASE64" | "8BIT" | "7BIT" | "B" => "ENCODING",
                    _ => "TYPE",
                };
                (key.to_string(), v)
            }
        })
        .collect();
    Some(Line { group, name: name.to_uppercase(), params, value: value.to_string() })
}

// ---------- Karten ----------

#[derive(Debug, Clone, Default)]
pub struct Card {
    /// Inhalt zwischen BEGIN:VCARD und END:VCARD
    pub lines: Vec<Line>,
}

/// Liest alle vCards aus einem Text (eine .vcf-Datei darf beliebig viele enthalten).
pub fn parse_cards(text: &str) -> Vec<Card> {
    let mut cards = Vec::new();
    let mut current: Option<Card> = None;
    let mut depth = 0usize; // AGENT-Karten in vCard 2.1 können verschachtelt sein
    for raw in unfold(text) {
        let Some(line) = parse_line(&raw) else { continue };
        let is_vcard = line.value.trim().eq_ignore_ascii_case("VCARD");
        match line.name.as_str() {
            "BEGIN" if is_vcard => {
                if current.is_none() {
                    current = Some(Card::default());
                    depth = 0;
                } else {
                    depth += 1;
                }
            }
            "END" if is_vcard => {
                if depth > 0 {
                    depth -= 1;
                } else if let Some(c) = current.take() {
                    cards.push(c);
                }
            }
            _ => {
                if let (Some(c), 0) = (current.as_mut(), depth) {
                    c.lines.push(line);
                }
            }
        }
    }
    cards
}

impl Card {
    pub fn get(&self, name: &str) -> Option<&Line> {
        self.lines.iter().find(|l| l.name == name)
    }

    pub fn text(&self, name: &str) -> String {
        self.get(name).map(|l| unescape(&l.value)).unwrap_or_default()
    }

    pub fn version(&self) -> &str {
        self.get("VERSION").map(|l| l.value.trim()).unwrap_or("3.0")
    }

    pub fn uid(&self) -> Option<String> {
        self.get("UID").map(|l| l.value.trim().to_string()).filter(|u| !u.is_empty())
    }

    pub fn kind(&self) -> String {
        self.get("KIND")
            .or_else(|| self.get("X-ADDRESSBOOKSERVER-KIND"))
            .map(|l| l.value.trim().to_lowercase())
            .filter(|k| !k.is_empty())
            .unwrap_or_else(|| "individual".into())
    }

    pub fn serialize(&self) -> String {
        let mut out = String::new();
        fold("BEGIN:VCARD", &mut out);
        // VERSION muss direkt auf BEGIN folgen
        if let Some(v) = self.get("VERSION") {
            v.serialize(&mut out);
        }
        for l in self.lines.iter().filter(|l| l.name != "VERSION") {
            l.serialize(&mut out);
        }
        fold("END:VCARD", &mut out);
        out
    }

    /// Ersetzt alle Zeilen mit einem der Namen an der Position der ersten davon.
    fn replace(&mut self, names: &[&str], new_lines: Vec<Line>) {
        let pos = self.lines.iter().position(|l| names.contains(&l.name.as_str())).unwrap_or(self.lines.len());
        let before = self.lines[..pos].len();
        let removed_before = self.lines[..pos].iter().filter(|l| names.contains(&l.name.as_str())).count();
        self.lines.retain(|l| !names.contains(&l.name.as_str()));
        let at = before - removed_before;
        for (i, l) in new_lines.into_iter().enumerate() {
            self.lines.insert(at + i, l);
        }
    }

    fn set(&mut self, name: &str, value: Option<String>) {
        self.replace(&[name], value.map(|v| Line::new(name, v)).into_iter().collect());
    }

    /// Gruppenzeilen wie `item1.X-ABLabel` entfernen, deren eigentliche Eigenschaft weg ist.
    fn drop_orphan_groups(&mut self) {
        let used: std::collections::HashSet<String> = self
            .lines
            .iter()
            .filter(|l| !l.name.starts_with("X-"))
            .filter_map(|l| l.group.clone())
            .collect();
        self.lines.retain(|l| match &l.group {
            Some(g) if l.name.starts_with("X-") => used.contains(g),
            _ => true,
        });
    }

    /// Freier Gruppenname `itemN`, der weder in der Karte noch in `extra` vorkommt.
    fn free_group(&self, extra: &[Line]) -> String {
        let taken = |g: &str| {
            self.lines.iter().chain(extra).any(|l| l.group.as_deref().is_some_and(|x| x.eq_ignore_ascii_case(g)))
        };
        (1..).map(|n| format!("item{n}")).find(|g| !taken(g)).unwrap()
    }

    /// Eigene Bezeichnung einer gruppierten Eigenschaft (`itemN.X-ABLabel`).
    fn label_of(&self, line: &Line) -> Option<String> {
        let g = line.group.as_ref()?;
        self.lines
            .iter()
            .find(|l| l.name == "X-ABLABEL" && l.group.as_ref().is_some_and(|x| x.eq_ignore_ascii_case(g)))
            .map(|l| unescape(&l.value))
    }
}

// ---------- Escaping ----------

pub fn unescape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut chars = s.chars();
    while let Some(c) = chars.next() {
        if c == '\\' {
            match chars.next() {
                Some('n') | Some('N') => out.push('\n'),
                Some(other) => out.push(other),
                None => {}
            }
        } else {
            out.push(c);
        }
    }
    out
}

pub fn escape(s: &str) -> String {
    s.replace('\\', "\\\\")
        .replace(';', "\\;")
        .replace(',', "\\,")
        .replace("\r\n", "\\n")
        .replace('\n', "\\n")
        .replace('\r', "")
}

/// Strukturierten Wert (N, ADR, ORG) an unescapten `;` teilen und entschlüsseln.
fn components(value: &str) -> Vec<String> {
    let mut parts = Vec::new();
    let mut cur = String::new();
    let mut chars = value.chars();
    while let Some(c) = chars.next() {
        match c {
            '\\' => {
                cur.push(c);
                if let Some(n) = chars.next() {
                    cur.push(n);
                }
            }
            ';' => parts.push(unescape(&std::mem::take(&mut cur))),
            _ => cur.push(c),
        }
    }
    parts.push(unescape(&cur));
    parts
}

fn join_components(parts: &[&str]) -> String {
    parts.iter().map(|p| escape(p.trim())).collect::<Vec<_>>().join(";")
}

// ---------- Datenmodell der Weboberfläche ----------

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct TypedValue {
    /// z. B. `mobile`, `home`, `work`, `other` oder eine eigene Bezeichnung
    #[serde(rename = "type", default)]
    pub kind: String,
    #[serde(default)]
    pub value: String,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct Address {
    #[serde(rename = "type")]
    pub kind: String,
    pub po_box: String,
    pub extended: String,
    pub street: String,
    pub locality: String,
    pub region: String,
    pub postal_code: String,
    pub country: String,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct ContactData {
    pub full_name: String,
    pub prefix: String,
    pub given_name: String,
    pub additional_names: String,
    pub family_name: String,
    pub suffix: String,
    pub nickname: String,
    pub organization: String,
    pub department: String,
    pub title: String,
    pub phones: Vec<TypedValue>,
    pub emails: Vec<TypedValue>,
    pub addresses: Vec<Address>,
    pub urls: Vec<TypedValue>,
    /// `YYYY-MM-DD` oder `--MM-DD` (ohne Jahr)
    pub birthday: String,
    pub note: String,
}

impl ContactData {
    /// Anzeigename aus Namensteilen, Firma, E-Mail oder Telefon ableiten.
    pub fn derived_name(&self) -> String {
        let name = [&self.prefix, &self.given_name, &self.additional_names, &self.family_name, &self.suffix]
            .iter()
            .map(|s| s.trim())
            .filter(|s| !s.is_empty())
            .collect::<Vec<_>>()
            .join(" ");
        let found = [name.as_str(), self.organization.trim()]
            .into_iter()
            .chain(self.emails.iter().map(|e| e.value.trim()))
            .chain(self.phones.iter().map(|e| e.value.trim()))
            .find(|s| !s.is_empty())
            .unwrap_or("")
            .to_string();
        found
    }

    pub fn normalize(&mut self) {
        for s in [
            &mut self.full_name, &mut self.prefix, &mut self.given_name, &mut self.additional_names,
            &mut self.family_name, &mut self.suffix, &mut self.nickname, &mut self.organization,
            &mut self.department, &mut self.title, &mut self.birthday,
        ] {
            *s = s.trim().to_string();
        }
        self.note = self.note.trim_end().to_string();
        for list in [&mut self.phones, &mut self.emails, &mut self.urls] {
            list.retain(|t| !t.value.trim().is_empty());
            for t in list.iter_mut() {
                t.value = t.value.trim().to_string();
                t.kind = normalize_kind(&t.kind);
            }
        }
        self.addresses.retain(|a| {
            [&a.po_box, &a.extended, &a.street, &a.locality, &a.region, &a.postal_code, &a.country]
                .iter()
                .any(|s| !s.trim().is_empty())
        });
        for a in &mut self.addresses {
            a.kind = normalize_kind(&a.kind);
        }
        if self.full_name.is_empty() {
            self.full_name = self.derived_name();
        }
    }
}

/// Bekannte Typen kleinschreiben, eigene Bezeichnungen unverändert lassen.
fn normalize_kind(kind: &str) -> String {
    let lower = kind.trim().to_lowercase();
    if lower.is_empty() || KNOWN_TEL.contains(&lower.as_str()) {
        lower
    } else {
        kind.trim().to_string()
    }
}

/// Apple-Bezeichnungen wie `_$!<Mobile>!$_` auf unsere Typen abbilden.
fn label_type(label: &str) -> String {
    let inner = label.trim().trim_start_matches("_$!<").trim_end_matches(">!$_");
    match inner.to_lowercase().as_str() {
        "mobile" | "iphone" | "cell" => "mobile".into(),
        "home" => "home".into(),
        "work" => "work".into(),
        "homefax" | "workfax" | "otherfax" | "fax" => "fax".into(),
        "pager" => "pager".into(),
        "other" => "other".into(),
        "homepage" => "home".into(),
        _ => label.trim().to_string(),
    }
}

fn entry_type(card: &Card, line: &Line) -> String {
    if let Some(label) = card.label_of(line) {
        return label_type(&label);
    }
    let types = line.types();
    let has = |t: &str| types.iter().any(|x| x == t);
    if line.name == "TEL" {
        if has("cell") {
            return "mobile".into();
        }
        if has("fax") {
            return "fax".into();
        }
        if has("pager") {
            return "pager".into();
        }
    }
    if has("work") {
        "work".into()
    } else if has("home") {
        "home".into()
    } else {
        "other".into()
    }
}

fn typed_value(card: &Card, line: &Line) -> TypedValue {
    let mut value = unescape(line.value.trim());
    if line.name == "TEL" {
        value = value.strip_prefix("tel:").unwrap_or(&value).to_string();
    }
    if line.name == "EMAIL" {
        value = value.strip_prefix("mailto:").unwrap_or(&value).to_string();
    }
    TypedValue { kind: entry_type(card, line), value: value.trim().to_string() }
}

fn address(card: &Card, line: &Line) -> Address {
    let c = components(&line.value);
    let part = |i: usize| c.get(i).map(|s| s.trim().to_string()).unwrap_or_default();
    Address {
        kind: entry_type(card, line),
        po_box: part(0),
        extended: part(1),
        street: part(2),
        locality: part(3),
        region: part(4),
        postal_code: part(5),
        country: part(6),
    }
}

/// Geburtstag in `YYYY-MM-DD` oder `--MM-DD` umwandeln.
fn normalize_bday(line: &Line) -> String {
    let v = line.value.trim();
    let v = v.split('T').next().unwrap_or(v);
    let digits: String = v.chars().filter(|c| c.is_ascii_digit()).collect();
    let omit_year = line.param("X-APPLE-OMIT-YEAR").is_some();
    if v.starts_with("--") && digits.len() == 4 {
        return format!("--{}-{}", &digits[..2], &digits[2..]);
    }
    if digits.len() == 8 {
        let (y, m, d) = (&digits[..4], &digits[4..6], &digits[6..]);
        return if omit_year || y == "1604" { format!("--{m}-{d}") } else { format!("{y}-{m}-{d}") };
    }
    v.to_string()
}

/// Strukturierte Daten aus einer Karte lesen.
pub fn extract(card: &Card) -> ContactData {
    let n = card.get("N").map(|l| components(&l.value)).unwrap_or_default();
    let n_part = |i: usize| n.get(i).map(|s| s.trim().to_string()).unwrap_or_default();
    let org = card.get("ORG").map(|l| components(&l.value)).unwrap_or_default();
    let all = |name: &'static str| card.lines.iter().filter(move |l| l.name == name);

    let mut data = ContactData {
        full_name: card.text("FN").trim().to_string(),
        family_name: n_part(0),
        given_name: n_part(1),
        additional_names: n_part(2),
        prefix: n_part(3),
        suffix: n_part(4),
        nickname: card.text("NICKNAME").trim().to_string(),
        organization: org.first().map(|s| s.trim().to_string()).unwrap_or_default(),
        department: org.get(1).map(|s| s.trim().to_string()).unwrap_or_default(),
        title: card.text("TITLE").trim().to_string(),
        phones: all("TEL").map(|l| typed_value(card, l)).collect(),
        emails: all("EMAIL").map(|l| typed_value(card, l)).collect(),
        addresses: all("ADR").map(|l| address(card, l)).collect(),
        urls: all("URL").map(|l| typed_value(card, l)).collect(),
        birthday: card.get("BDAY").map(normalize_bday).unwrap_or_default(),
        note: all("NOTE").map(|l| unescape(&l.value)).collect::<Vec<_>>().join("\n").trim_end().to_string(),
    };
    data.phones.retain(|t| !t.value.is_empty());
    data.emails.retain(|t| !t.value.is_empty());
    data.urls.retain(|t| !t.value.is_empty());
    if data.full_name.is_empty() {
        data.full_name = data.derived_name();
    }
    data
}

/// Foto als data:-URL (für die Anzeige in der Weboberfläche).
pub fn photo(card: &Card) -> Option<String> {
    let line = card.get("PHOTO")?;
    let v = line.value.trim();
    if v.starts_with("data:") || v.starts_with("http://") || v.starts_with("https://") {
        return Some(v.to_string());
    }
    let enc = line.param("ENCODING").unwrap_or("").to_lowercase();
    if enc == "b" || enc == "base64" {
        let ty = line
            .types()
            .into_iter()
            .find(|t| !t.is_empty())
            .map(|t| t.trim_start_matches("image/").to_string())
            .unwrap_or_else(|| "jpeg".into());
        let data: String = v.chars().filter(|c| !c.is_whitespace()).collect();
        return Some(format!("data:image/{ty};base64,{data}"));
    }
    None
}

/// Setzt oder entfernt das Foto. `data_url` ist eine data:-URL (`data:image/jpeg;base64,…`) oder leer
/// zum Entfernen. Gibt `false` zurück, wenn die URL nicht verwendbar ist.
pub fn set_photo(card: &mut Card, data_url: &str) -> bool {
    let data_url = data_url.trim();
    if data_url.is_empty() {
        card.replace(&["PHOTO"], vec![]);
        return true;
    }
    let Some((head, data)) = data_url.strip_prefix("data:").and_then(|r| r.split_once(',')) else {
        return false;
    };
    let Some(mime) = head.strip_suffix(";base64") else { return false };
    let ty = mime.trim().trim_start_matches("image/").to_uppercase();
    if ty.is_empty() || !ty.chars().all(|c| c.is_ascii_alphanumeric() || c == '+' || c == '-') {
        return false;
    }
    let data: String = data.chars().filter(|c| !c.is_whitespace()).collect();
    let valid = !data.is_empty() && data.chars().all(|c| c.is_ascii_alphanumeric() || "+/=".contains(c));
    if !valid {
        return false;
    }
    let line = if card.version().starts_with('4') {
        Line::new("PHOTO", format!("data:{};base64,{data}", mime.trim()))
    } else {
        Line { params: vec![("ENCODING".into(), "b".into()), ("TYPE".into(), ty)], ..Line::new("PHOTO", data) }
    };
    card.replace(&["PHOTO"], vec![line]);
    true
}

/// Text für die Suche (kleingeschrieben).
pub fn search_text(d: &ContactData) -> String {
    let mut parts = vec![
        d.full_name.clone(), d.nickname.clone(), d.organization.clone(), d.department.clone(), d.title.clone(),
    ];
    parts.extend(d.emails.iter().map(|e| e.value.clone()));
    parts.extend(d.phones.iter().map(|e| e.value.clone()));
    parts.extend(d.phones.iter().map(|e| e.value.chars().filter(|c| c.is_ascii_digit() || *c == '+').collect()));
    parts.extend(d.addresses.iter().map(|a| format!("{} {} {}", a.street, a.postal_code, a.locality)));
    parts.join(" ").to_lowercase()
}

// ---------- Schreiben ----------

const KNOWN_TEL: [&str; 6] = ["mobile", "home", "work", "fax", "pager", "other"];
const KNOWN_OTHER: [&str; 3] = ["home", "work", "other"];

fn type_params(name: &str, kind: &str) -> Vec<&'static str> {
    match (name, kind) {
        ("TEL", "mobile") => vec!["CELL"],
        ("TEL", "fax") => vec!["FAX"],
        ("TEL", "pager") => vec!["PAGER"],
        (_, "home") => vec!["HOME"],
        (_, "work") => vec!["WORK"],
        _ => vec![],
    }
}

fn is_known(name: &str, kind: &str) -> bool {
    kind.is_empty() || if name == "TEL" { KNOWN_TEL.contains(&kind) } else { KNOWN_OTHER.contains(&kind) }
}

/// Ersetzt die Zeilen einer mehrfachen Eigenschaft (TEL, EMAIL, URL, ADR). Unveränderte Einträge
/// behalten ihre Originalzeile samt Parametern und Gruppen-Bezeichnungen.
fn replace_multi<T: PartialEq>(
    card: &mut Card,
    name: &str,
    new: &[T],
    read: impl Fn(&Card, &Line) -> T,
    write: impl Fn(&T) -> (String, String),
) {
    let old: Vec<(Line, T)> =
        card.lines.iter().filter(|l| l.name == name).map(|l| (l.clone(), read(card, l))).collect();
    let mut used = vec![false; old.len()];
    let mut lines = Vec::new();
    let mut labels = Vec::new();
    for entry in new {
        if let Some(i) = old.iter().enumerate().position(|(i, (_, e))| !used[i] && e == entry) {
            used[i] = true;
            lines.push(old[i].0.clone());
            continue;
        }
        let (kind, value) = write(entry);
        let mut line = Line::new(name, value);
        if is_known(name, &kind) {
            line = line.with_types(&type_params(name, &kind));
        } else {
            // Eigene Bezeichnung wie bei Apple/DAVx⁵: itemN.TEL + itemN.X-ABLabel
            let g = card.free_group(&labels);
            line.group = Some(g.clone());
            labels.push(Line { group: Some(g), ..Line::new("X-ABLABEL", escape(&kind)) });
        }
        lines.push(line);
    }
    lines.extend(labels);
    card.replace(&[name], lines);
}

/// Übernimmt die geänderten Felder (`new` gegenüber `old`) in die Karte.
pub fn apply(card: &mut Card, old: &ContactData, new: &ContactData) {
    let v4 = card.version().starts_with('4');
    let opt = |s: &str| (!s.trim().is_empty()).then(|| escape(s.trim()));

    if new.full_name != old.full_name {
        card.set("FN", Some(escape(&new.full_name)));
    }
    let n_new = [&new.family_name, &new.given_name, &new.additional_names, &new.prefix, &new.suffix];
    let n_old = [&old.family_name, &old.given_name, &old.additional_names, &old.prefix, &old.suffix];
    if n_new != n_old || card.get("N").is_none() {
        let parts: Vec<&str> = n_new.iter().map(|s| s.as_str()).collect();
        card.set("N", Some(join_components(&parts)));
    }
    if new.nickname != old.nickname {
        card.set("NICKNAME", opt(&new.nickname));
    }
    if (&new.organization, &new.department) != (&old.organization, &old.department) {
        let org = if new.department.is_empty() {
            opt(&new.organization)
        } else {
            Some(join_components(&[&new.organization, &new.department]))
        };
        card.set("ORG", org);
    }
    if new.title != old.title {
        card.set("TITLE", opt(&new.title));
    }
    if new.note != old.note {
        card.set("NOTE", (!new.note.trim().is_empty()).then(|| escape(&new.note)));
    }
    if new.birthday != old.birthday {
        let b = new.birthday.trim();
        let line = match b.strip_prefix("--") {
            Some(md) if v4 => Some(Line::new("BDAY", format!("--{}", md.replace('-', "")))),
            // vCard 3.0 kennt kein Datum ohne Jahr: Apple-Konvention, die auch DAVx⁵ versteht
            Some(md) => Some(Line {
                params: vec![("X-APPLE-OMIT-YEAR".into(), "1604".into())],
                ..Line::new("BDAY", format!("1604-{md}"))
            }),
            None if b.is_empty() => None,
            None => Some(Line::new("BDAY", b)),
        };
        card.replace(&["BDAY"], line.into_iter().collect());
    }

    if new.phones != old.phones {
        replace_multi(card, "TEL", &new.phones, typed_value, |t| (t.kind.clone(), escape(&t.value)));
    }
    if new.emails != old.emails {
        replace_multi(card, "EMAIL", &new.emails, typed_value, |t| (t.kind.clone(), escape(&t.value)));
    }
    if new.urls != old.urls {
        replace_multi(card, "URL", &new.urls, typed_value, |t| (t.kind.clone(), t.value.clone()));
    }
    if new.addresses != old.addresses {
        replace_multi(card, "ADR", &new.addresses, address, |a| {
            let parts = [&a.po_box, &a.extended, &a.street, &a.locality, &a.region, &a.postal_code, &a.country];
            (a.kind.clone(), join_components(&parts.map(|s| s.as_str())))
        });
    }
    card.drop_orphan_groups();
}

pub fn rev_value(now: DateTime<Utc>) -> String {
    now.format("%Y%m%dT%H%M%SZ").to_string()
}

/// Neue Karte (vCard 3.0) aus den Formulardaten.
pub fn create(uid: &str, data: &ContactData, now: DateTime<Utc>) -> Card {
    let mut card = Card {
        lines: vec![
            Line::new("VERSION", "3.0"),
            Line::new("PRODID", "-//Synchaven//Synchaven//DE"),
            Line::new("UID", uid),
        ],
    };
    apply(&mut card, &ContactData::default(), data);
    card.set("REV", Some(rev_value(now)));
    card
}

// ---------- Import ----------

fn qp_decode(s: &str) -> Vec<u8> {
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        if b[i] == b'=' && i + 2 < b.len() {
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
    out
}

fn decode_charset(bytes: Vec<u8>, charset: Option<&str>) -> String {
    let latin = charset.is_some_and(|c| {
        let c = c.to_lowercase();
        c.contains("8859") || c.contains("1252") || c == "latin1"
    });
    if latin {
        return bytes.iter().map(|&b| b as char).collect();
    }
    match String::from_utf8(bytes) {
        Ok(s) => s,
        // Kein gültiges UTF-8: wahrscheinlich Latin-1 ohne CHARSET-Angabe
        Err(e) => e.into_bytes().iter().map(|&b| b as char).collect(),
    }
}

const STRUCTURED: [&str; 3] = ["N", "ADR", "ORG"];
const TEXT: [&str; 9] = ["FN", "NICKNAME", "TITLE", "ROLE", "NOTE", "LABEL", "CATEGORIES", "X-ABLABEL", "SORT-STRING"];

/// Bereitet eine importierte Karte zum Speichern vor: vCard 2.1 wird in 3.0 umgewandelt,
/// fehlende Pflichtfelder (UID, FN, N) werden ergänzt.
pub fn normalize_import(mut card: Card, new_uid: impl FnOnce() -> String) -> Card {
    let v21 = card.version().starts_with('2');
    for line in &mut card.lines {
        let qp = line.param("ENCODING").is_some_and(|e| e.eq_ignore_ascii_case("QUOTED-PRINTABLE"));
        let charset = line.param("CHARSET").map(str::to_string);
        if qp || charset.is_some() {
            let bytes = if qp { qp_decode(&line.value) } else { line.value.clone().into_bytes() };
            line.value = decode_charset(bytes, charset.as_deref());
        }
        line.params.retain(|(k, v)| {
            k != "CHARSET" && !(k == "ENCODING" && (v.eq_ignore_ascii_case("QUOTED-PRINTABLE") || v.contains("BIT")))
        });
        for (k, v) in &mut line.params {
            if k == "ENCODING" && v.eq_ignore_ascii_case("BASE64") {
                *v = "b".into();
            }
        }
        if line.param("ENCODING") == Some("b") {
            line.value.retain(|c| !c.is_whitespace());
        }
        if v21 {
            // In vCard 2.1 sind Kommas nicht escapt, Semikolons trennen nur strukturierte Werte.
            if STRUCTURED.contains(&line.name.as_str()) {
                let parts: Vec<String> = split_unquoted(&line.value, ';')
                    .into_iter()
                    .map(|p| escape(&p.replace("\\;", ";")))
                    .collect();
                line.value = parts.join(";");
            } else if TEXT.contains(&line.name.as_str()) {
                line.value = escape(&line.value.replace("\\;", ";").replace("\\n", "\n").replace("\\N", "\n"));
            }
            // TYPE-Werte zusammenfassen: TEL;TYPE=CELL;TYPE=PREF → TEL;TYPE=CELL,PREF
            let types: Vec<String> =
                line.params.iter().filter(|(k, _)| k == "TYPE").map(|(_, v)| v.clone()).collect();
            if types.len() > 1 {
                line.params.retain(|(k, _)| k != "TYPE");
                line.params.push(("TYPE".into(), types.join(",")));
            }
        }
    }
    if v21 {
        card.set("VERSION", Some("3.0".into()));
    }
    if card.get("VERSION").is_none() {
        card.lines.insert(0, Line::new("VERSION", "3.0"));
    }
    if card.uid().is_none() {
        card.set("UID", Some(new_uid()));
    }
    let data = extract(&card);
    if card.text("FN").trim().is_empty() {
        let name = if data.full_name.is_empty() { "(Ohne Namen)".to_string() } else { data.full_name.clone() };
        card.set("FN", Some(escape(&name)));
    }
    if card.get("N").is_none() && !card.version().starts_with('4') {
        card.set("N", Some(";;;;".into()));
    }
    card
}

#[cfg(test)]
mod tests {
    use super::*;

    const V3: &str = "BEGIN:VCARD\r\nVERSION:3.0\r\nPRODID:-//DAVx5\r\nUID:abc-1\r\nFN:Erika Mustermann\r\nN:Mustermann;Erika;;Dr.;\r\nORG:Firma\\, GmbH;Vertrieb\r\nitem1.TEL:+49 30 1234\r\nitem1.X-ABLabel:Zentrale\r\nTEL;TYPE=CELL:0171 555\r\nEMAIL;TYPE=WORK:erika@example.org\r\nADR;TYPE=HOME:;;Heidestr. 17;Köln;;51147;Deutschland\r\nBDAY:1964-08-12\r\nNOTE:Zeile 1\\nZeile 2\r\nPHOTO;ENCODING=b;TYPE=JPEG:/9j/4AAQ\r\n SkZJRg==\r\nX-CUSTOM:bleibt\r\nEND:VCARD\r\n";

    #[test]
    fn reads_v3() {
        let cards = parse_cards(V3);
        assert_eq!(cards.len(), 1);
        let d = extract(&cards[0]);
        assert_eq!(d.full_name, "Erika Mustermann");
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
        assert_eq!(d.full_name, "Jürgen Müller, Sen.");
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
    fn apple_birthday_without_year() {
        let card = parse_cards("BEGIN:VCARD\nVERSION:3.0\nFN:A\nBDAY;X-APPLE-OMIT-YEAR=1604:1604-05-12\nEND:VCARD\n").remove(0);
        assert_eq!(extract(&card).birthday, "--05-12");
    }
}
