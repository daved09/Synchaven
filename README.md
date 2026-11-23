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
* **Erinnerungen** pro Termin und als Standard pro Kalender (voreingestellt 15 Minuten vorher, ganztägig am Tag um
  09:00; auch für Geburtstage): als Push-Benachrichtigung im
  Browser – auch bei geschlossenem Tab – und als Android-Benachrichtigung in der App
* iCalendar-Import und -Export (.ics) – kompatibel mit Google Kalender, Outlook, Thunderbird, Apple
* Ansichten: Monat, Woche, Tag, Terminliste mit Suche
* **Aufgaben** wie im Google Kalender: mehrere farbige Aufgabenlisten, Aufgaben mit Fälligkeit (ganztägig oder mit
  Uhrzeit) erscheinen im Kalender und lassen sich dort abhaken und per Drag & Drop verschieben; Aufgabenbereich mit
  Schnelleingabe, auch für Aufgaben ohne Datum
* **Kontakte:** mehrere Adressbücher, Suche, Telefon/E-Mail/Adressen/Websites mit Typen und eigenen Bezeichnungen,
  Geburtstag (auch ohne Jahr), Notizen, Kategorien (zum Filtern, umbenennbar; in DAVx⁵ als Kontaktgruppen), Fotos aus importierten oder synchronisierten Kontakten
* **Geburtstagskalender:** Geburtstage aller Kontakte erscheinen automatisch als jährliche Termine im eigenen
  Kalender „Geburtstage“ (mit Alter, sofern das Geburtsjahr bekannt ist) – auch für per DAVx⁵ synchronisierte Kontakte
* vCard-Import und -Export (.vcf, auch per Drag & Drop) – vCard 2.1, 3.0 und 4.0, z. B. aus Android, iPhone,
  Google Kontakte, Outlook, Thunderbird; erneuter Import erzeugt keine Duplikate
* **Synchronisierung per CardDAV** mit DAVx⁵ (Android), Thunderbird und iOS – in beide Richtungen
* Material 3 mit dynamischem Farbschema aus frei wählbarer Grundfarbe, Hell/Dunkel/System
* Tastenkürzel: `M` `W` `T` `L` (Ansicht), `H` (heute), `←`/`→` (blättern), `C` (neuer Termin), `A` (neue Aufgabe), `/` (Suche)

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
| `ADMIN_EMAILS`       | *(leer)*          | Administratoren, kommagetrennt: dürfen die ganze Datenbank exportieren/importieren |
| `VAPID_SUBJECT`      | `mailto:synchaven@example.org` | Kontaktadresse für Push-Dienste (`mailto:` oder `https:`) |
| `RUST_LOG`           | `synchaven=info`  | Log-Level |
| `PUID` / `PGID`      | `10001`           | Benutzer-/Gruppen-ID für Programm und `/data` (nur Docker) |

**Hinweis:** Für den Betrieb im Internet einen Reverse-Proxy mit HTTPS (z. B. Caddy, Traefik, nginx)
vorschalten und nach dem Anlegen der eigenen Konten `ALLOW_REGISTRATION=false` setzen.

### Benachrichtigungen im Browser

Erinnerungen werden per Web Push verschickt: Unter *Konto → Einstellungen → Benachrichtigungen* lässt sich jeder
Browser einzeln anmelden, dort gibt es auch eine Testbenachrichtigung. Voraussetzungen:

* Die Weboberfläche muss über **HTTPS** aufgerufen werden (oder `localhost`) – sonst sperren Browser Benachrichtigungen.
* Der Server muss die Push-Dienste der Browser erreichen (z. B. `fcm.googleapis.com`, `updates.push.services.mozilla.com`,
  `web.push.apple.com`). Für einige Dienste sollte `VAPID_SUBJECT` eine echte Kontaktadresse sein.
* iPhone/iPad (ab iOS 16.4): Synchaven zuerst über *Teilen → Zum Home-Bildschirm* installieren und dort öffnen.

Der Schlüssel des Servers (VAPID) liegt in `vapid_private_key` im Datenverzeichnis. Geht er verloren, müssen die
Browser Benachrichtigungen neu einschalten.

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

#### Export und Import als JSON

Zusätzlich lässt sich die gesamte Datenbank (alle Benutzer, Kalender, Termine, Adressbücher, Kontakte,
Push-Abonnements) als JSON exportieren – gedacht vor allem als Rückfallebene vor einem Update, falls eine
Migration fehlschlägt. Die Termine im Geburtstagskalender fehlen darin; sie werden beim Import aus den Kontakten neu
erzeugt.

**In der Weboberfläche:** Die eigene Adresse in `ADMIN_EMAILS` eintragen; dann gibt es unter
*Konto → Einstellungen → Verwaltung* die Knöpfe *Datenbank exportieren* und *Datenbank importieren*. Der Import
ersetzt alle Daten aller Benutzer. Nach einer fehlgeschlagenen Migration: alte Datenbank zur Seite legen (siehe
unten), Server starten, mit der Admin-Adresse ein Konto registrieren (das erste Konto geht auch bei
`ALLOW_REGISTRATION=false`) und den Export importieren – das vorläufige Konto wird dabei ersetzt.

**Auf der Kommandozeile** – der Export geht auch bei laufendem Server:

```bash
docker exec synchaven synchaven export > synchaven-export.json
```

Der Import legt eine leere Datenbank mit dem Schema der aktuellen Version an und übernimmt die Daten. Spalten oder
Tabellen, die es nicht mehr gibt, werden mit einer Warnung übersprungen, neue Spalten erhalten ihren Standardwert.
Der Server muss dafür gestoppt sein. Vorgehen nach einer fehlgeschlagenen Migration:

```bash
docker compose stop
# Alte Datenbank zur Seite legen (synchaven.db, synchaven.db-wal, synchaven.db-shm)
docker compose run --rm --no-deps --entrypoint sh Synchaven \
  -c 'mkdir -p /data/alt && mv /data/synchaven.db* /data/alt/'
docker compose run --rm --no-deps -v "$PWD":/backup Synchaven import /backup/synchaven-export.json
docker compose up -d
```

Enthält die Datenbank schon Daten, bricht der Import ab; `import --replace …` löscht sie vorher. JWT-Secret und
VAPID-Schlüssel liegen als Dateien in `/data` und sind nicht im Export – bleiben sie erhalten, bleiben auch
Anmeldungen und Push-Abonnements gültig. Ohne Docker: `DATA_DIR=./data synchaven export datei.json` bzw.
`synchaven import datei.json`.

## REST API

Die vollständige API-Referenz steht in [docs/API.md](docs/API.md). Kurzüberblick:

| Bereich   | Endpunkte |
|-----------|-----------|
| Konto     | `POST /api/auth/register`, `POST /api/auth/login`, `POST /api/auth/refresh`, `GET/PUT /api/auth/me` |
| Kalender  | `GET/POST /api/calendars`, `GET/PUT/DELETE /api/calendars/{id}`, `GET …/export`, `POST …/import` |
| Termine   | `GET /api/events?start&end`, `POST /api/events`, `GET/PUT/DELETE /api/events/{id}`, `POST /api/events/{id}/exdates` |
| Aufgaben  | `GET/POST /api/task-lists`, `PUT/DELETE /api/task-lists/{id}`, `GET/POST /api/tasks`, `GET/PUT/DELETE /api/tasks/{id}`, `PUT /api/tasks/{id}/completed` |
| Adressbücher | `GET/POST /api/addressbooks`, `PUT/DELETE /api/addressbooks/{id}`, `GET …/export`, `POST …/import` |
| Kontakte  | `GET /api/contacts[?addressbook_id&q]`, `POST /api/contacts`, `GET/PUT/DELETE /api/contacts/{id}` |
| CardDAV   | `/dav/` (Basic-Auth, siehe [docs/API.md](docs/API.md#carddav-davx-thunderbird-ios)) |

Wiederkehrende Termine werden vom Server in Einzelvorkommen expandiert, sodass Clients online keine eigene
RRULE-Logik benötigen (die Android-App expandiert nur offline selbst, aus `GET /calendars/{id}/events`).

## Android-App

Unter `Android/` liegt eine native App (Kotlin, Jetpack Compose, Material 3) mit denselben Funktionen wie die
Weboberfläche: Anmeldung/Registrierung, Monats-, Wochen-, Tages- und Listenansicht, Termine anlegen, bearbeiten,
löschen (auch einzelne Vorkommen) und per langem Drücken verschieben, Wiederholungen, Suche, Kalender verwalten,
.ics-Import/-Export, Kontakte mit Adressbüchern und .vcf-Import/-Export.

**Aufgaben:** Aufgaben mit Fälligkeit erscheinen in Monat, Woche/Tag und Terminliste mit Abhak-Symbol (erledigte
durchgestrichen); Antippen öffnet die Details mit *Als erledigt markieren*, in der Terminliste hakt das Symbol direkt
ab, im Zeitraster lassen sie sich per langem Drücken verschieben. Aufgabenlisten werden in der Seitenleiste ein- und
ausgeblendet, bearbeitet und angelegt; *Alle Aufgaben* öffnet den Aufgabenbereich mit Schnelleingabe und Erledigt-
Bereich, und der Erstellen-Button bietet *Termin* oder *Aufgabe*. Auch im lokalen Modus
verfügbar.

**Synchronisierung mit Android (ersetzt DAVx⁵):** Die App legt ein Android-Konto „Synchaven“ an und trägt Kalender
und Kontakte selbst in den Android-Kalender und die Systemkontakte ein – über eigene SyncAdapter und die REST API,
in beide Richtungen. Änderungen in der Kalender- oder Kontakte-App von Android werden hochgeladen, Änderungen auf
dem Server periodisch (einstellbar, Standard stündlich) und nach jeder Änderung in der App übernommen. Einschalten
unter *Einstellungen → Mit Android synchronisieren*, getrennt für Kalender und Kontakte. Das Passwort liegt dafür im
Android-Konto, damit sich die Synchronisierung nach Ablauf des Tokens selbst neu anmelden kann.

* Serien werden mit RRULE/EXDATE übertragen; ein auf dem Gerät geändertes oder abgesagtes Einzelvorkommen wird auf
  dem Server zu einem eigenen Termin bzw. einer EXDATE der Serie (wie beim .ics-Import).
* Der Geburtstagskalender ist auf dem Gerät schreibgeschützt.
* Kontakte aller Adressbücher landen im Konto; auf dem Gerät neu angelegte Kontakte kommen ins erste Adressbuch.
  Übertragen werden Name, Spitzname, Firma, Telefon, E-Mail, Adressen, Websites, Geburtstag, Notiz und Foto –
  die Kontakte-App bietet für Synchaven-Kontakte nur diese Felder an.
* Wer bisher DAVx⁵ für dieselben Adressbücher genutzt hat, sollte dort das Konto entfernen, sonst erscheinen
  Kontakte doppelt.

**Erinnerungen:** Die App zeigt Erinnerungen an Termine als Android-Benachrichtigung – geplant aus dem lokalen
Cache, daher auch offline und im lokalen Modus. Ein- und ausschalten unter *Einstellungen → Benachrichtigungen*; ab
Android 13 fragt die App einmal nach der Berechtigung. Änderungen aus der Weboberfläche kommen beim nächsten Abgleich
an (beim Öffnen der App, spätestens etwa alle vier Stunden). In den Android-Kalender werden keine Erinnerungen
übertragen, damit sie nicht doppelt erscheinen.

**Offline-Betrieb:** Kalender, alle Termine (Serien mit RRULE/EXDATE), Adressbücher und Kontakte liegen zusätzlich in
einem lokalen Cache auf dem Gerät; Kontaktfotos werden beim Öffnen eines Kontakts gespeichert. Ist der Server nicht
erreichbar (unterwegs, Server aus), zeigt die App die gespeicherten Daten – Vorkommen von Serien und das Alter im
Geburtstagskalender berechnet sie dann selbst – und ein Hinweis über der Navigationsleiste macht den Offline-Zustand
sichtbar. Termine und Kontakte lassen sich auch offline anlegen, bearbeiten, verschieben und löschen, Kalender ein- und
ausblenden und die Standardansicht wechseln; ebenso Aufgaben anlegen, bearbeiten, abhaken und löschen und
Aufgabenlisten ein-/ausblenden. Diese Änderungen landen in einer Warteschlange und werden in Reihenfolge
übertragen, sobald der Server wieder erreichbar ist (bei Netzwechsel, beim Öffnen der App, jede Minute im Vordergrund
oder über *Einstellungen → Offline-Daten → Jetzt abgleichen*); danach lädt die App alles neu. Lehnt der Server eine
Änderung ab (z. B. weil der Termin inzwischen gelöscht wurde), wird sie verworfen und das angezeigt. Nur online gehen:
Kalender, Adressbücher und Aufgabenlisten anlegen/löschen, Import/Export, Profil. Beim Abmelden wird der Cache gelöscht; läuft nur das
Token ab, bleibt er erhalten und ausstehende Änderungen werden nach der erneuten Anmeldung übertragen.

**Ohne Server (lokaler Modus):** Mit *Ohne Server verwenden* auf dem Anmeldebildschirm funktioniert die App ganz
ohne Weboberfläche. Kalender, Termine, Aufgaben, Adressbücher und Kontakte liegen dann nur auf dem Gerät (`filesDir/local/`,
damit in den Android-Sicherungen enthalten) – mit allen Funktionen: Kalender, Aufgabenlisten und Adressbücher verwalten, Serien,
Geburtstagskalender aus den Kontakten, .ics- und .vcf-Import/-Export und die Synchronisierung mit dem Android-Kalender
und den Systemkontakten (Konto „Synchaven (lokal)“). Technisch ersetzt `data/local/LocalApi.kt` die REST API: Sie
speichert im selben lokalen Speicher wie der Offline-Cache und prüft Eingaben wie der Server; iCalendar, vCard und
Geburtstage sind Portierungen aus `backend/src`. Beim vCard-Import bleiben nur die Felder erhalten, die die App kennt.
*Einstellungen → Mit Server verbinden* wechselt zur Anmeldung; die lokalen Daten bleiben auf dem Gerät und sind beim
nächsten *Ohne Server verwenden* wieder da – auf einen Server übertragen werden sie nicht (dafür: Export und Import; Aufgaben sind darin nicht
enthalten).

Beim ersten Start die Adresse des Servers eintragen (z. B. `http://homeserver.local:8080`) oder *Ohne Server verwenden*
wählen. Unverschlüsseltes HTTP ist für Server im Heimnetz erlaubt; selbst installierte Zertifizierungsstellen werden
akzeptiert.

```bash
cd Android
./gradlew assembleRelease          # → app/build/outputs/apk/release/SyncHaven.apk
./gradlew testDebugUnitTest        # Unit-Tests der Datums-, Wiederholungs-, Kontakt-, Sync-, Offline- und Dateiformat-Logik
```

Aufbau: `data/` (Retrofit-API, Repositories, DataStore für Sitzung und Einstellungen), `data/offline/` (lokaler
Cache je Konto, Warteschlange offline gemachter Änderungen, Abgleich mit dem Server), `data/local/` (lokaler Modus
ohne Server: LocalApi, iCalendar, vCard, Geburtstage), `ui/<bereich>/`
(Compose-Bildschirme mit je einem ViewModel), `util/` (Portierung der Hilfslogik aus `frontend/src/utils`),
`sync/` (Android-Konto, SyncAdapter für CalendarContract und ContactsContract).

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
