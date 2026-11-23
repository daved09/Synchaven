# Synchaven REST API (v1)

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
{ "name": "Synchaven", "version": "1.0.0", "api_version": 1, "allow_registration": true }
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
  "user": { "id": "…", "email": "anna@example.com", "name": "Anna", "default_view": "month", "created_at": "…", "is_admin": false }
}
```
`default_view` ist die Standardansicht des Kalenders (`month`, `week`, `day` oder `list`). Weboberfläche und App
speichern dort die zuletzt gewählte Ansicht. `is_admin` ist `true` für Adressen aus `ADMIN_EMAILS`.
Für neue Konten wird automatisch der Kalender „Persönlich“ angelegt. Bei `ALLOW_REGISTRATION=false` lässt sich nur
das erste Konto einer leeren Datenbank registrieren (`allow_registration` in `/info` zeigt, ob es gerade geht).

### `POST /auth/login`
```json
{ "email": "anna@example.com", "password": "…" }
```

### `POST /auth/refresh` → wie Login
Liefert ein neues Token (gleiche Antwort wie `/auth/login`) für eine noch gültige Anmeldung. Die Android-App erneuert
so das Token ihrer Synchronisierung, ohne das Passwort zu speichern.

### `GET /auth/me` → `User`

### `PUT /auth/me` → `User`
Alle Felder optional. Für Änderung von `email` oder `new_password` ist `current_password` Pflicht.
```json
{ "name": "Anna B.", "email": "neu@example.com", "current_password": "…", "new_password": "…", "default_view": "week" }
```

## Kalender

`Calendar`:
```json
{
  "id": "uuid", "name": "Arbeit", "color": "#0B57D0", "description": "",
  "visible": true, "kind": "default",
  "default_reminders": [10], "default_all_day_reminders": [900],
  "created_at": "…", "updated_at": "…"
}
```

`kind` ist `default` oder `birthdays`. Jeder Benutzer hat genau einen **Geburtstagskalender** („Geburtstage“), den
der Server automatisch anlegt und aus den Kontakten befüllt: Für jeden Kontakt mit Geburtstag gibt es einen
ganztägigen, jährlichen Termin (`🎂 Name`, am 29.02. Geborene in Nicht-Schaltjahren am 28.02.). Änderungen an
Kontakten – über die API oder CardDAV – werden sofort übernommen. Name, Farbe und Sichtbarkeit lassen sich ändern;
Löschen, Import sowie Anlegen, Bearbeiten und Löschen einzelner Termine werden mit `400` abgelehnt.

| Methode | Pfad                         | Body              | Antwort        |
|---------|------------------------------|-------------------|----------------|
| GET     | `/calendars`                 | –                 | `Calendar[]`   |
| POST    | `/calendars`                 | `CalendarInput`   | `201 Calendar` |
| GET     | `/calendars/{id}`            | –                 | `Calendar`     |
| PUT     | `/calendars/{id}`            | `CalendarInput`   | `Calendar`     |
| DELETE  | `/calendars/{id}`            | –                 | `204` (inkl. aller Termine) |
| GET     | `/calendars/{id}/events`     | –                 | `Event[]` (Serien nicht expandiert) |
| GET     | `/calendars/{id}/export`     | –                 | `text/calendar` (.ics) |
| POST    | `/calendars/{id}/import?timezone=Europe/Berlin` | .ics-Datei als Body (`text/calendar`) | `{ "imported": 12, "skipped": [] }` |

`CalendarInput`: `{ "name": "Arbeit", "color": "#0B57D0", "description": "", "visible": true, "default_reminders": [10], "default_all_day_reminders": [900] }`
(alles außer `name` optional). Fehlen `default_reminders` bzw. `default_all_day_reminders`, bleiben die bisherigen
Standard-Erinnerungen erhalten (siehe [Erinnerungen](#erinnerungen)). Neue Kalender – auch der Geburtstagskalender –
erinnern standardmäßig 15 Minuten vorher (`[15]`) bzw. bei ganztägigen Terminen am Tag selbst um 09:00 (`[-540]`).

`/calendars/{id}/events` liefert alle Termine eines Kalenders als `Event` – Serien als ein Eintrag mit `rrule` und
`exdates`. Gedacht für Clients, die selbst synchronisieren (die Android-App überträgt damit in den Android-Kalender).

Beim Export enthält jeder Termin seine Erinnerungen (eigene oder Kalender-Standard) als `VALARM`; beim Import
werden relative `TRIGGER` übernommen. Termine ohne `VALARM` behalten beim erneuten Import ihre Erinnerungen.

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
  "rrule": "FREQ=WEEKLY;BYDAY=MO;UNTIL=20261214T225959Z",
  "contact_id": null,
  "read_only": false,
  "reminders": null
}
```
`id` ist pro Vorkommen eindeutig; zum Bearbeiten/Löschen wird `event_id` verwendet.
`reminders` sind die Erinnerungen des Termins wie bei `Event` (`null` = Standard des Kalenders).
Die Ausblendung über `Calendar.visible` erfolgt clientseitig.

Geburtstage haben `read_only: true` und in `contact_id` die ID des Kontakts. Ist das Geburtsjahr bekannt, enthält
der Titel des Vorkommens das Alter, z. B. `🎂 Erika Mustermann (62)`. `Event` enthält dieselben beiden Felder.

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
  "exdates": [],
  "reminders": [10, 1440]
}
```
`description`, `location`, `all_day`, `timezone` (Standard `UTC`), `rrule`, `exdates`, `reminders` sind optional.

`reminders`: Liste von Erinnerungen in Minuten vor Beginn (siehe [Erinnerungen](#erinnerungen)). `null` bedeutet
„Standard-Erinnerungen des Kalenders“, `[]` „keine Erinnerung“. **Fehlt das Feld, bleiben die bisherigen
Erinnerungen unverändert** – neue Termine verwenden dann den Kalender-Standard. So überschreiben Clients ohne
Erinnerungsunterstützung (z. B. die Synchronisierung mit dem Android-Kalender) nichts.

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

## Erinnerungen

Eine Erinnerung ist eine Minutenzahl vor Beginn (höchstens 5 pro Termin, höchstens 4 Wochen = 40320 Minuten).
Bei **ganztägigen** Terminen bezieht sie sich auf Mitternacht in der Zeitzone des Empfängers; negative Werte sind
eine Uhrzeit am Tag selbst – wie `TRIGGER` in iCalendar:

| Wert   | Termin mit Uhrzeit   | Ganztägiger Termin       |
|--------|----------------------|--------------------------|
| `0`    | zum Beginn           | am Tag um 00:00          |
| `10`   | 10 Minuten vorher    | am Vortag um 23:50       |
| `900`  | 15 Stunden vorher    | am Vortag um 09:00       |
| `-540` | ungültig             | am Tag selbst um 09:00   |

Für Termine ohne eigene Erinnerungen (`reminders: null`, z. B. alle Geburtstage) gelten die Standard-Erinnerungen
ihres Kalenders: `default_reminders` für Termine mit Uhrzeit, `default_all_day_reminders` für ganztägige.
Voreingestellt sind 15 Minuten vorher bzw. am Tag selbst um 09:00 – so hat jeder Termin eine Erinnerung, solange
sie nicht am Termin oder im Kalender geändert wird.

Die Android-App plant Erinnerungen selbst (aus ihrem lokalen Cache). Für Browser verschickt der Server sie per
**Web Push**:

| Methode | Pfad                    | Body | Antwort |
|---------|-------------------------|------|---------|
| GET     | `/push/public-key`      | –    | `{ "public_key": "B…" }` (VAPID, Base64url, ohne Anmeldung) |
| POST    | `/push/subscriptions`   | `{ "endpoint": "https://…", "keys": { "p256dh": "…", "auth": "…" }, "timezone": "Europe/Berlin" }` | `204` |
| DELETE  | `/push/subscriptions`   | `{ "endpoint": "https://…" }` | `204` |
| POST    | `/push/test`            | `{ "endpoint": "https://…" }` (optional, sonst alle Browser des Kontos) | `{ "sent": 1 }` |

`endpoint` und `keys` stammen aus `PushSubscription.toJSON()`; `timezone` bestimmt den Beginn ganztägiger Termine.
Ein Endpunkt gehört immer dem zuletzt angemeldeten Konto. Der Server prüft alle 30 Sekunden auf fällige
Erinnerungen und sendet sie verschlüsselt (RFC 8291) als JSON:

```json
{
  "type": "reminder", "tag": "<event_id>_<start>_<minuten>", "event_id": "…", "title": "Zahnarzt",
  "location": "", "start": "2026-11-26T13:30:00Z", "end": "2026-11-26T14:00:00Z", "all_day": false, "minutes": 10
}
```
Abgelaufene Abonnements (Antwort `404`/`410` des Push-Dienstes) werden automatisch entfernt.

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
  "note": "",
  "categories": ["Familie", "Verein"]
}
```
Alle Felder außer `addressbook_id` sind optional. `full_name` wird immer aus den Namensteilen gebildet; ein
mitgeschickter Wert gilt nur, wenn alle Namensteile leer sind, ersatzweise Firma, E-Mail oder Telefon. `type`: Telefon `mobile|home|work|fax|pager|other`, sonst `home|work|other` –
andere Werte werden als eigene Bezeichnung gespeichert. `birthday`: `YYYY-MM-DD` oder `--MM-DD` (ohne Jahr).
Ändert sich `addressbook_id`, wird der Kontakt verschoben.

Optional `photo`: neues Foto als data:-URL (`data:image/jpeg;base64,…`), `""` entfernt das Foto. Fehlt das Feld (oder
ist es `null`), bleibt das Foto unverändert.

`categories`: Kategorien des Kontakts (vCard `CATEGORIES`, von DAVx⁵ als Kontaktgruppen übernommen), höchstens 100
mit je bis zu 100 Zeichen. Leerzeichen am Rand und Doppelte (ohne Groß-/Kleinschreibung) werden entfernt. Fehlt das
Feld (oder ist es `null`), bleiben die Kategorien unverändert. Die Suche (`q`) findet auch Kategorien.

`Contact` entspricht dem Input plus `id`, `uid`, `etag`, `has_photo`, `photo?`, `created_at`, `updated_at`. `etag`
ändert sich mit jeder Änderung des Kontakts (auch des Fotos oder per CardDAV) und eignet sich zum Abgleich.

### `DELETE /contacts/{id}` → `204`

## Kontaktkategorien

Kategorien gibt es nur an den Kontakten; die Liste aller Kategorien ergibt sich aus `GET /contacts`. Groß-/
Kleinschreibung von `name` spielt keine Rolle. Betroffen sind alle Adressbücher des Benutzers.

### `POST /contact-categories/rename` → `{ "updated": 3 }`
Body `{ "name": "Freunde", "new_name": "Freundeskreis" }`. Gibt es `new_name` am Kontakt schon, werden beide zusammengeführt.

### `POST /contact-categories/delete` → `{ "updated": 3 }`
Body `{ "name": "Freunde" }`. Entfernt die Kategorie aus allen Kontakten; die Kontakte bleiben erhalten.

## Verwaltung (nur `ADMIN_EMAILS`, sonst `403`)

### `GET /admin/export` → `application/json`
Die ganze Datenbank (alle Benutzer inkl. Passwort-Hashes) als Datei `synchaven-export-<Datum>.json`. Termine im
Geburtstagskalender sind nicht enthalten, der Import erzeugt sie aus den Kontakten neu. Gleiches
Format wie `synchaven export` auf der Kommandozeile:
```json
{ "format": "synchaven-export", "format_version": 1, "app_version": "1.0.0", "exported_at": "…", "migration": 1,
  "tables": { "users": [ { "id": "…", "email": "…", … } ], "calendars": [ … ], … } }
```

### `POST /admin/import?replace=true` (Body: Export, `Content-Type: application/json`, max. 512 MB)
Ersetzt in einer Transaktion **alle** Daten aller Benutzer. Ohne `replace=true` → `409`, solange die Datenbank
Daten enthält; ungültige Datei oder unstimmige Daten → `400`, die Datenbank bleibt dann unverändert. Tabellen und
Spalten, die es nicht mehr gibt, werden übersprungen. Tokens bleiben gültig, solange es den Benutzer danach noch gibt.
```json
{ "summary": ["users: 2", "calendars: 3", "events: 120", …, "Geburtstage: 35"], "warnings": ["Spalten von users gibt es nicht mehr, …"] }
```

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
