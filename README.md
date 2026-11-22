# Synchaven

Synchaven ist eine selbst gehostete Kalender- und Kontaktverwaltung mit **Rust-Backend** (axum + SQLite), **Vue-3-Weboberfläche im
Material-3-Design** (Vuetify), einer **REST API** für eigene Clients wie eine Android-App und einem
**CardDAV-Server** für die Kontakt-Synchronisierung mit DAVx⁵.

## Funktionen

* Mehrbenutzerfähig mit Registrierung/Anmeldung (Argon2-Passwörter, JWT-Bearer-Tokens)
* Mehrere farbige Kalender pro Benutzer, ein-/ausblendbar
* Ganztägige, mehrtägige und zeitgebundene Termine
* Wiederkehrende Termine (RFC 5545 RRULE: täglich, wöchentlich, monatlich, jährlich, benutzerdefiniert),
  einzelne Vorkommen löschbar; korrekt über Sommer-/Winterzeitwechsel
* iCalendar-Import und -Export (.ics) – kompatibel mit Google Kalender, Outlook, Thunderbird, Apple
* Ansichten: Monat, Woche, Tag, Terminliste mit Suche
* **Kontakte:** mehrere Adressbücher, Suche, Telefon/E-Mail/Adressen/Websites mit Typen und eigenen Bezeichnungen,
  Geburtstag (auch ohne Jahr), Notizen, Fotos aus importierten oder synchronisierten Kontakten
* **Geburtstagskalender:** Geburtstage aller Kontakte erscheinen automatisch als jährliche Termine im eigenen
  Kalender „Geburtstage“ (mit Alter, sofern das Geburtsjahr bekannt ist) – auch für per DAVx⁵ synchronisierte Kontakte
* vCard-Import und -Export (.vcf, auch per Drag & Drop) – vCard 2.1, 3.0 und 4.0, z. B. aus Android, iPhone,
  Google Kontakte, Outlook, Thunderbird; erneuter Import erzeugt keine Duplikate
* **Synchronisierung per CardDAV** mit DAVx⁵ (Android), Thunderbird und iOS – in beide Richtungen
* Material 3 mit dynamischem Farbschema aus frei wählbarer Grundfarbe, Hell/Dunkel/System
* Tastenkürzel: `M` `W` `T` `L` (Ansicht), `H` (heute), `←`/`→` (blättern), `C` (neuer Termin), `/` (Suche)

## Start mit Docker

Zuerst das Image bauen, dann starten:

```bash
./build.sh
docker compose up -d
```

Danach <http://localhost:8080> öffnen und ein Konto registrieren. Die Daten (SQLite-Datenbank und
JWT-Secret) liegen im Volume `synchaven-data` unter `/data`.

Statt des Volumes kann auch ein Verzeichnis des Hosts eingebunden werden (`./data:/data`). Dann `PUID` und
`PGID` auf die IDs des Besitzers setzen (`id -u` / `id -g`): Der Container passt beim Start die Rechte von
`/data` an und führt das Programm mit diesen IDs aus, nie als root. Wird der Container per `user:` gestartet,
entfällt die Anpassung – das Verzeichnis muss dann diesem Benutzer gehören.

Nach Codeänderungen: `./build.sh && docker compose up -d` – Compose ersetzt den Container dann
durch einen mit dem neuen Image.

### Build-Script

`build.sh` braucht keine Parameter. Es baut das Image als
`ghcr.io/daved09/synchaven:<version>`, wobei `<version>` aus `version.properties`
(Eintrag `version=…`) stammt, und schreibt die Version als `SYNCHAVEN_VERSION` in `.env`. Diese Datei
liest `docker compose` automatisch, daher startet Compose immer die zuletzt gebaute Version. Für eine
neue Version nur `version` in `version.properties` erhöhen.

Das Backend liest dieselbe Datei beim Kompilieren über `backend/build.rs` und meldet die Version unter
`GET /api/info` – auch bei `cargo run` ohne `build.sh`. Die Version in `backend/Cargo.toml` ist nur ein
Platzhalter (`0.0.0`).

Hochladen in die Registry:

```bash
docker push ghcr.io/daved09/synchaven:1.0.0
```

Auf einem anderen Rechner genügen `docker-compose.yml` und eine `.env` mit
`SYNCHAVEN_VERSION=1.0.0` – das Image wird dann aus der Registry geladen.

Ohne Compose:

```bash
./build.sh
docker run -d --name synchaven -p 8080:8080 -v synchaven-data:/data ghcr.io/daved09/synchaven:1.0.0
```

### Konfiguration (Umgebungsvariablen)

| Variable             | Standard          | Beschreibung |
|----------------------|-------------------|--------------|
| `BIND_ADDR`          | `0.0.0.0:8080`    | Adresse/Port |
| `DATA_DIR`           | `/data`           | Ablage für `synchaven.db` und `jwt_secret` |
| `ALLOW_REGISTRATION` | `true`            | Neue Konten erlauben |
| `TOKEN_TTL_DAYS`     | `30`              | Gültigkeit der Anmeldung |
| `JWT_SECRET`         | *(automatisch)*   | Eigenes Secret (≥ 32 Zeichen) |
| `CORS_ORIGINS`       | `*`               | Erlaubte Browser-Origins, kommagetrennt |
| `RUST_LOG`           | `synchaven=info`  | Log-Level |
| `PUID` / `PGID`      | `10001`           | Benutzer-/Gruppen-ID für Programm und `/data` (nur Docker) |

**Hinweis:** Für den Betrieb im Internet einen Reverse-Proxy mit HTTPS (z. B. Caddy, Traefik, nginx)
vorschalten und nach dem Anlegen der eigenen Konten `ALLOW_REGISTRATION=false` setzen.

### Kontakte mit DAVx⁵ synchronisieren

1. In DAVx⁵ auf **+** tippen und **Mit URL und Benutzername anmelden** wählen.
2. Basis-URL `https://<server>/dav/` (die Startadresse funktioniert ebenfalls), Benutzername = E-Mail-Adresse,
   Passwort = Anmeldepasswort.
3. Konto anlegen und im Reiter **CardDAV** die Adressbücher aktivieren.

Dieselben Angaben zeigt die Weboberfläche unter *Kontakte → Mit DAVx⁵ synchronisieren*. Da das Passwort bei
jeder Anfrage mitgesendet wird, gehört der Server für den Zugriff über das Internet hinter HTTPS.

### Sicherung

```bash
docker run --rm -v synchaven-data:/data -v "$PWD":/backup debian:bookworm-slim \
  tar czf /backup/synchaven-backup.tgz -C /data .
```

## REST API

Die vollständige API-Referenz steht in [docs/API.md](docs/API.md). Kurzüberblick:

| Bereich   | Endpunkte |
|-----------|-----------|
| Konto     | `POST /api/auth/register`, `POST /api/auth/login`, `GET/PUT /api/auth/me` |
| Kalender  | `GET/POST /api/calendars`, `GET/PUT/DELETE /api/calendars/{id}`, `GET …/export`, `POST …/import` |
| Termine   | `GET /api/events?start&end`, `POST /api/events`, `GET/PUT/DELETE /api/events/{id}`, `POST /api/events/{id}/exdates` |
| Adressbücher | `GET/POST /api/addressbooks`, `PUT/DELETE /api/addressbooks/{id}`, `GET …/export`, `POST …/import` |
| Kontakte  | `GET /api/contacts[?addressbook_id&q]`, `POST /api/contacts`, `GET/PUT/DELETE /api/contacts/{id}` |
| CardDAV   | `/dav/` (Basic-Auth, siehe [docs/API.md](docs/API.md#carddav-davx-thunderbird-ios)) |

Wiederkehrende Termine werden vom Server in Einzelvorkommen expandiert, sodass eine Android-App
keine eigene RRULE-Logik benötigt.

## Android-App

Unter `Android/` liegt eine native App (Kotlin, Jetpack Compose, Material 3) mit denselben Funktionen wie die
Weboberfläche: Anmeldung/Registrierung, Monats-, Wochen-, Tages- und Listenansicht, Termine anlegen, bearbeiten,
löschen (auch einzelne Vorkommen) und per langem Drücken verschieben, Wiederholungen, Suche, Kalender verwalten,
.ics-Import/-Export, Kontakte mit Adressbüchern, .vcf-Import/-Export und eine Anleitung für DAVx⁵.

Beim ersten Start die Adresse des Servers eintragen (z. B. `http://homeserver.local:8080`). Unverschlüsseltes HTTP ist
für Server im Heimnetz erlaubt; selbst installierte Zertifizierungsstellen werden akzeptiert.

```bash
cd Android
./gradlew assembleRelease          # → app/build/outputs/apk/release/SyncHaven.apk
./gradlew testDebugUnitTest        # Unit-Tests der Datums-, Wiederholungs- und Kontaktlogik
```

Aufbau: `data/` (Retrofit-API, Repositories, DataStore für Sitzung und Einstellungen), `ui/<bereich>/`
(Compose-Bildschirme mit je einem ViewModel), `util/` (Portierung der Hilfslogik aus `frontend/src/utils`).

## Entwicklung

Voraussetzungen: Rust (stable), Node.js ≥ 20.

```bash
# Backend (http://localhost:8080)
cd backend && cargo run

# Frontend mit Hot-Reload (http://localhost:5173, leitet /api an das Backend weiter)
cd frontend && npm install && npm run dev
```

Tests und Prüfungen:

```bash
cd backend && cargo test
cd frontend && npm run typecheck && npm run build
```

## Projektstruktur

```
backend/            Rust (axum, sqlx/SQLite, rrule, jsonwebtoken, argon2, quick-xml)
  migrations/       SQL-Schema (wird beim Start automatisch angewendet)
  src/routes/       REST-Endpunkte (auth, calendars, events, ical, contacts)
  src/recurrence.rs Expansion wiederkehrender Termine
  src/ical.rs       iCalendar-Import/-Export
  src/vcard.rs      vCard lesen/schreiben (inkl. 2.1-Import), verlustfreies Bearbeiten
  src/contacts.rs   Speicherung von Adressbüchern und Kontakten
  src/birthdays.rs  Geburtstagskalender aus den Kontakten
  src/dav.rs        CardDAV-Server für DAVx⁵ & Co.
frontend/           Vue 3 + Vuetify 3 (Material 3) + Pinia
  src/components/   Monats-, Wochen-/Tages-, Listenansicht, Kontaktdetails, Dialoge
  src/views/        Anmeldung, Kalender, Kontakte
  src/plugins/      Vuetify-Setup und M3-Farbschema-Generator
Android/            Android-App (Kotlin, Jetpack Compose, Material 3, MVVM)
docs/API.md         API-Referenz
Dockerfile          Mehrstufiger Build → schlankes Debian-Image
```
