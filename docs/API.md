# Kalender REST API (v1)

Basis-URL: `http://<host>:8080/api` · Format: JSON (UTF-8) · Zeitangaben: RFC 3339 / ISO 8601.

## Authentifizierung

Alle Endpunkte außer `/info`, `/auth/register` und `/auth/login` benötigen den Header

```
Authorization: Bearer <token>
```

Tokens sind JWTs (HS256) und standardmäßig 30 Tage gültig (`TOKEN_TTL_DAYS`). Bei abgelaufenem oder
ungültigem Token antwortet der Server mit `401` – die App sollte dann zur Anmeldung zurückkehren.

## Fehlerformat

```json
{ "error": "bad_request", "message": "Der Titel muss 1–500 Zeichen lang sein" }
```

| HTTP | `error`        | Bedeutung                                  |
|------|----------------|--------------------------------------------|
| 400  | `bad_request`  | Ungültige Eingabe (`message` ist anzeigbar) |
| 401  | `unauthorized` | Nicht angemeldet / falsches Passwort        |
| 403  | `forbidden`    | z. B. Registrierung deaktiviert             |
| 404  | `not_found`    | Nicht vorhanden oder gehört anderem Benutzer|
| 409  | `conflict`     | E-Mail bereits registriert                  |
| 500  | `internal`     | Serverfehler                                |

## Zeitmodell

* **Zeitgebundene Termine**: `start`/`end` sind absolute Zeitpunkte (in Antworten immer UTC, `…Z`).
  Eingaben dürfen beliebige Offsets haben (`2026-11-23T09:00:00+01:00`).
  `timezone` (IANA, z. B. `Europe/Berlin`) bestimmt, in welcher Zeitzone Wiederholungen berechnet
  werden – ein wöchentlicher Termin um 09:00 bleibt so auch nach der Zeitumstellung um 09:00.
* **Ganztägige Termine** (`all_day: true`): `start`/`end` sind Mitternacht **UTC** des Datums,
  `end` ist **exklusiv**. Ein eintägiger Termin am 24.12.: `start = 2026-12-24T00:00:00Z`,
  `end = 2026-12-25T00:00:00Z`. Clients lesen nur den Datumsteil.
* **Wiederholungen**: `rrule` ist eine RFC-5545-RRULE ohne Präfix, z. B. `FREQ=WEEKLY;BYDAY=MO,WE;COUNT=10`.
  `UNTIL` darf als Datum (`UNTIL=20261231`) übergeben werden und wird serverseitig in UTC umgerechnet.
  `exdates` enthält Startzeitpunkte ausgelassener Vorkommen.

---

## Allgemein

### `GET /info`
```json
{ "name": "Kalender", "version": "0.1.0", "api_version": 1, "allow_registration": true }
```

## Konto

### `POST /auth/register` → `201`
```json
{ "email": "anna@example.com", "password": "mind. 8 Zeichen", "name": "Anna" }
```
Antwort (auch bei Login):
```json
{
  "token": "eyJ…",
  "token_type": "Bearer",
  "expires_at": "2026-12-22T18:56:04Z",
  "user": { "id": "…", "email": "anna@example.com", "name": "Anna", "created_at": "…" }
}
```
Für neue Konten wird automatisch der Kalender „Persönlich“ angelegt.

### `POST /auth/login`
```json
{ "email": "anna@example.com", "password": "…" }
```

### `GET /auth/me` → `User`

### `PUT /auth/me` → `User`
Alle Felder optional. Für Änderung von `email` oder `new_password` ist `current_password` Pflicht.
```json
{ "name": "Anna B.", "email": "neu@example.com", "current_password": "…", "new_password": "…" }
```

## Kalender

`Calendar`:
```json
{
  "id": "uuid", "name": "Arbeit", "color": "#0B57D0", "description": "",
  "visible": true, "created_at": "…", "updated_at": "…"
}
```

| Methode | Pfad                         | Body              | Antwort        |
|---------|------------------------------|-------------------|----------------|
| GET     | `/calendars`                 | –                 | `Calendar[]`   |
| POST    | `/calendars`                 | `CalendarInput`   | `201 Calendar` |
| GET     | `/calendars/{id}`            | –                 | `Calendar`     |
| PUT     | `/calendars/{id}`            | `CalendarInput`   | `Calendar`     |
| DELETE  | `/calendars/{id}`            | –                 | `204` (inkl. aller Termine) |
| GET     | `/calendars/{id}/export`     | –                 | `text/calendar` (.ics) |
| POST    | `/calendars/{id}/import?timezone=Europe/Berlin` | .ics-Datei als Body (`text/calendar`) | `{ "imported": 12, "skipped": [] }` |

`CalendarInput`: `{ "name": "Arbeit", "color": "#0B57D0", "description": "", "visible": true }`
(`color`, `description`, `visible` optional).

Beim Import werden Termine mit gleicher `UID` aktualisiert statt dupliziert. `timezone` gilt für
Zeitangaben ohne `TZID` („floating time“). Geänderte Einzelvorkommen (`RECURRENCE-ID`) werden als
eigene Termine angelegt und in der Serie ausgenommen.

## Termine

### `GET /events?start=…&end=…[&calendar_ids=a,b][&q=Suchtext]` → `Occurrence[]`

Liefert alle Termine, die den Zeitraum `[start, end)` berühren (max. 800 Tage). Wiederkehrende
Termine werden **serverseitig in einzelne Vorkommen expandiert** – die App muss keine RRULEs auswerten.

```json
{
  "id": "6d1…_1795424400",
  "event_id": "6d1…",
  "calendar_id": "…",
  "title": "Jour fixe",
  "description": "",
  "location": "Raum 3",
  "start": "2026-11-30T08:00:00Z",
  "end": "2026-11-30T09:00:00Z",
  "all_day": false,
  "timezone": "Europe/Berlin",
  "recurring": true,
  "rrule": "FREQ=WEEKLY;BYDAY=MO;UNTIL=20261214T225959Z"
}
```
`id` ist pro Vorkommen eindeutig; zum Bearbeiten/Löschen wird `event_id` verwendet.
Die Ausblendung über `Calendar.visible` erfolgt clientseitig.

### `POST /events` → `201 Event`
```json
{
  "calendar_id": "…",
  "title": "Zahnarzt",
  "description": "",
  "location": "",
  "start": "2026-11-26T14:30:00+01:00",
  "end": "2026-11-26T15:00:00+01:00",
  "all_day": false,
  "timezone": "Europe/Berlin",
  "rrule": null,
  "exdates": []
}
```
`description`, `location`, `all_day`, `timezone` (Standard `UTC`), `rrule`, `exdates` sind optional.

`Event` (Serie bzw. Einzeltermin) entspricht dem Input plus `id`, `uid`, `created_at`, `updated_at`.

### `GET /events/{id}` → `Event`
### `PUT /events/{id}` → `Event`  (vollständiger Body wie bei POST; ändert bei Serien die ganze Serie)
### `DELETE /events/{id}` → `204`  (bei Serien: ganze Serie)

### `POST /events/{id}/exdates` → `Event`
Entfernt ein einzelnes Vorkommen aus einer Serie:
```json
{ "occurrence_start": "2026-12-07T08:00:00Z" }
```
`occurrence_start` ist das `start` des Vorkommens aus `GET /events`.

---

## Adressbücher

### `GET /addressbooks` → `AddressBook[]`
Legt beim ersten Aufruf ein Adressbuch „Kontakte“ an, falls noch keines existiert.
```json
{ "id": "…", "name": "Kontakte", "description": "", "contact_count": 42, "created_at": "…", "updated_at": "…" }
```

### `POST /addressbooks` → `201 AddressBook`  ·  `PUT /addressbooks/{id}` → `AddressBook`
```json
{ "name": "Arbeit", "description": "" }
```

### `DELETE /addressbooks/{id}` → `204`  (löscht alle enthaltenen Kontakte)

### `GET /addressbooks/{id}/export` → `text/vcard`
Alle Kontakte als eine .vcf-Datei.

### `POST /addressbooks/{id}/import` (Body: .vcf-Text, `Content-Type: text/vcard`)
Liest beliebig viele Karten (vCard 2.1, 3.0 und 4.0). vCard 2.1 (Quoted-Printable, Zeichensätze) wird in 3.0
umgewandelt. Kontakte mit gleicher UID werden aktualisiert; Karten ohne UID erhalten eine aus dem Inhalt abgeleitete,
sodass ein erneuter Import derselben Datei keine Duplikate erzeugt.
```json
{ "imported": 120, "created": 118, "updated": 2, "unchanged": 0, "skipped": [] }
```

## Kontakte

Kontakte werden intern als vollständige vCard gespeichert. Die API liefert die gängigen Felder strukturiert;
beim Speichern werden nur die geänderten Felder in der vCard ersetzt – Fotos, Gruppen und Felder anderer
Programme bleiben erhalten.

### `GET /contacts[?addressbook_id=…][&q=Suchtext]` → `Contact[]`
Ohne Fotos (siehe `has_photo`), sortiert nach Anzeigename. Kontaktgruppen werden nicht geliefert.

### `GET /contacts/{id}` → `Contact` (inkl. `photo` als data:-URL)

### `POST /contacts` → `201 Contact`  ·  `PUT /contacts/{id}` → `Contact`
```json
{
  "addressbook_id": "…",
  "full_name": "",
  "prefix": "Dr.", "given_name": "Erika", "additional_names": "", "family_name": "Mustermann", "suffix": "",
  "nickname": "",
  "organization": "Beispiel GmbH", "department": "Vertrieb", "title": "Leitung",
  "phones": [{ "type": "mobile", "value": "+49 171 1234567" }],
  "emails": [{ "type": "work", "value": "erika@example.org" }],
  "addresses": [{
    "type": "home", "po_box": "", "extended": "", "street": "Heidestr. 17", "locality": "Köln",
    "region": "", "postal_code": "51147", "country": "Deutschland"
  }],
  "urls": [{ "type": "work", "value": "https://example.org" }],
  "birthday": "1964-08-12",
  "note": ""
}
```
Alle Felder außer `addressbook_id` sind optional. Ein leerer `full_name` wird aus den Namensteilen (ersatzweise
Firma, E-Mail, Telefon) gebildet. `type`: Telefon `mobile|home|work|fax|pager|other`, sonst `home|work|other` –
andere Werte werden als eigene Bezeichnung gespeichert. `birthday`: `YYYY-MM-DD` oder `--MM-DD` (ohne Jahr).
Ändert sich `addressbook_id`, wird der Kontakt verschoben.

`Contact` entspricht dem Input plus `id`, `uid`, `has_photo`, `photo?`, `created_at`, `updated_at`.

### `DELETE /contacts/{id}` → `204`

## CardDAV (DAVx⁵, Thunderbird, iOS)

Unabhängig von der REST API stellt der Server Adressbücher per CardDAV (RFC 6352) bereit. Anmeldung per HTTP Basic
mit E-Mail-Adresse und Passwort. Basis-URL: `https://<server>/dav/` (Discovery auch über `/.well-known/carddav`).

| Pfad | Inhalt |
|------|--------|
| `/dav/principals/{user_id}/` | Principal mit `addressbook-home-set` |
| `/dav/addressbooks/{user_id}/` | alle Adressbücher |
| `/dav/addressbooks/{user_id}/{addressbook_id}/` | Adressbuch (`getctag`, `addressbook-multiget`, `addressbook-query`) |
| `/dav/addressbooks/{user_id}/{addressbook_id}/{name}.vcf` | Kontakt (`GET`, `PUT` mit `If-Match`/`If-None-Match`, `DELETE`) |

Adressbücher lassen sich per `MKCOL`, `PROPPATCH` (Name, Beschreibung) und `DELETE` auch vom Client verwalten.
Änderungen über die REST API sind sofort per CardDAV sichtbar und umgekehrt.

---

## Beispiel mit curl

```bash
B=http://localhost:8080/api
TOKEN=$(curl -s -X POST $B/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"anna@example.com","password":"geheim123"}' | jq -r .token)
curl -s "$B/events?start=2026-11-01T00:00:00Z&end=2026-12-01T00:00:00Z" -H "Authorization: Bearer $TOKEN"

# Kontakte aus einer .vcf-Datei ins erste Adressbuch importieren
BOOK=$(curl -s $B/addressbooks -H "Authorization: Bearer $TOKEN" | jq -r '.[0].id')
curl -s -X POST "$B/addressbooks/$BOOK/import" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: text/vcard' --data-binary @kontakte.vcf
```
