package de.dave.synchaven.data.local

import de.dave.synchaven.data.api.ApiException
import de.dave.synchaven.data.api.NoServerConfiguredException
import de.dave.synchaven.data.api.SyncHavenApi
import de.dave.synchaven.data.model.AddressBook
import de.dave.synchaven.data.model.AddressBookInput
import de.dave.synchaven.data.model.AuthResponse
import de.dave.synchaven.data.model.CalEvent
import de.dave.synchaven.data.model.Calendar
import de.dave.synchaven.data.model.CalendarInput
import de.dave.synchaven.data.model.Contact
import de.dave.synchaven.data.model.ContactImportResult
import de.dave.synchaven.data.model.ContactInput
import de.dave.synchaven.data.model.EventInput
import de.dave.synchaven.data.model.ExdateRequest
import de.dave.synchaven.data.model.ImportResult
import de.dave.synchaven.data.model.LoginRequest
import de.dave.synchaven.data.model.Occurrence
import de.dave.synchaven.data.model.RegisterRequest
import de.dave.synchaven.data.model.ServerInfo
import de.dave.synchaven.data.model.UpdateMeRequest
import de.dave.synchaven.data.model.User
import de.dave.synchaven.data.offline.OfflineData
import de.dave.synchaven.data.offline.OfflineStore
import de.dave.synchaven.util.RRuleExpander
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/** Schlüssel des [OfflineStore] für die Daten des lokalen Modus */
const val LOCAL_ACCOUNT = "local"

/**
 * „Server“ des lokalen Modus: dieselbe Schnittstelle wie die REST API, aber gespeichert im [OfflineStore] unter
 * [LOCAL_ACCOUNT]. So funktionieren Repositories und die Synchronisierung mit Android ohne Änderungen auch ganz ohne
 * Weboberfläche. Prüfungen und Fehlermeldungen entsprechen denen des Servers (siehe backend/src/routes).
 */
class LocalApi(
    private val store: OfflineStore,
    /** Standardansicht aus der Sitzung (für [me]) */
    private val defaultView: () -> String,
) : SyncHavenApi {

    // ---------- Speicher ----------

    private suspend fun data(): OfflineData {
        store.awaitReady()
        if (store.currentAccount != LOCAL_ACCOUNT) throw NoServerConfiguredException()
        return store.data.value
    }

    /** Ändert die lokalen Daten atomar; [transform] darf mit [bad]/[notFound] abbrechen. */
    private suspend fun edit(transform: (OfflineData) -> OfflineData): OfflineData {
        data()
        return store.update(transform)
    }

    /** Legt beim ersten Öffnen einen Standardkalender und ein Adressbuch an (wie die Registrierung beim Server). */
    suspend fun ensureInitialized() {
        edit { d ->
            val ts = now()
            d.copy(
                calendars = d.calendars ?: listOf(
                    Calendar(newId(), "Persönlich", "#6750A4", createdAt = ts, updatedAt = ts),
                ),
                events = d.events ?: emptyList(),
                addressBooks = d.addressBooks?.takeIf { it.isNotEmpty() } ?: listOf(AddressBook(newId(), DEFAULT_BOOK)),
                contacts = d.contacts ?: emptyList(),
            ).let { Birthdays.sync(it, ::newId, ts) }.recount()
        }
    }

    // ---------- Konto ----------

    override suspend fun info(server: String): ServerInfo = localInfo()
    override suspend fun currentInfo(): ServerInfo = localInfo()
    override suspend fun login(server: String, body: LoginRequest): AuthResponse = unavailable()
    override suspend fun register(server: String, body: RegisterRequest): AuthResponse = unavailable()
    override suspend fun refreshToken(): AuthResponse = unavailable()
    override suspend fun me(): User = User(id = LOCAL_ACCOUNT, email = "", defaultView = defaultView())

    /** Lokal gibt es nur die Standardansicht; die speichert die Sitzung. */
    override suspend fun updateMe(body: UpdateMeRequest): User {
        if (body.name != null || body.email != null || body.newPassword != null) unavailable()
        return me().let { u -> body.defaultView?.let { u.copy(defaultView = it) } ?: u }
    }

    private fun localInfo() = ServerInfo(name = "Synchaven (lokal)")

    // ---------- Kalender ----------

    override suspend fun calendars(): List<Calendar> = data().calendars.orEmpty()

    override suspend fun createCalendar(body: CalendarInput): Calendar {
        val (name, color) = validateCalendar(body)
        val ts = now()
        val cal = Calendar(newId(), name, color ?: "#6750A4", body.description.trim(), body.visible, createdAt = ts, updatedAt = ts)
        edit { d -> d.copy(calendars = d.calendars.orEmpty() + cal) }
        return cal
    }

    override suspend fun updateCalendar(id: String, body: CalendarInput): Calendar {
        val (name, color) = validateCalendar(body)
        var result: Calendar? = null
        edit { d ->
            val old = d.calendars.orEmpty().firstOrNull { it.id == id } ?: notFound()
            val cal = old.copy(
                name = name, color = color ?: old.color, description = body.description.trim(), visible = body.visible,
                updatedAt = now(),
            )
            result = cal
            d.copy(calendars = d.calendars.orEmpty().map { if (it.id == id) cal else it })
        }
        return result!!
    }

    override suspend fun deleteCalendar(id: String) {
        edit { d ->
            val cal = d.calendars.orEmpty().firstOrNull { it.id == id } ?: notFound()
            if (cal.isBirthdays) bad("Der Geburtstagskalender kann nicht gelöscht, aber ausgeblendet werden")
            d.copy(calendars = d.calendars.orEmpty().filterNot { it.id == id }, events = d.events.orEmpty().filterNot { it.calendarId == id })
        }
    }

    override suspend fun calendarEvents(id: String): List<CalEvent> {
        val d = data()
        calendar(d, id)
        return d.events.orEmpty().filter { it.calendarId == id }
    }

    override suspend fun exportCalendar(id: String): ResponseBody {
        val d = data()
        val cal = calendar(d, id)
        val events = d.events.orEmpty().filter { it.calendarId == id }.sortedBy { it.start }
        return ICal.export(cal.name, events).toResponseBody("text/calendar; charset=utf-8".toMediaType())
    }

    /** Wie `POST /calendars/{id}/import`: Termine mit gleicher UID werden aktualisiert, geänderte Einzelvorkommen
     *  einer Serie werden eigene Termine und in der Serie ausgenommen. */
    override suspend fun importCalendar(id: String, timezone: String, ics: RequestBody): ImportResult {
        ensureWritable(calendar(data(), id))
        val zone = ICal.zoneOrNull(timezone) ?: ZoneOffset.UTC
        val parsed = try {
            ICal.parse(ics.readText(), zone)
        } catch (e: IllegalArgumentException) {
            bad(e.message.orEmpty())
        }
        val (masters, overrides) = parsed.partition { it.recurrenceId == null }
        var imported = 0
        val skipped = mutableListOf<String>()
        edit { start ->
            var d = start
            fun input(e: ICal.ParsedEvent, rrule: String?) = EventInput(
                calendarId = id, title = e.title, description = e.description, location = e.location,
                start = e.start.toString(), end = e.end.toString(), allDay = e.allDay, timezone = e.timezone,
                rrule = rrule, exdates = e.exdates.map { it.toString() },
            )
            fun upsert(uid: String, ev: (id: String) -> CalEvent) {
                val existing = d.events.orEmpty().firstOrNull { it.calendarId == id && it.uid == uid }
                val new = ev(existing?.id ?: newId()).copy(uid = uid)
                d = d.copy(events = if (existing == null) d.events.orEmpty() + new else d.events.orEmpty().map { if (it.id == existing.id) new else it })
                imported++
            }
            for (e in masters) {
                val uid = e.uid?.takeIf { it.isNotEmpty() } ?: newUid()
                // Eine ungültige RRULE soll nicht den ganzen Termin verwerfen
                val ev = runCatching { validateEvent(input(e, e.rrule), "") }.recoverCatching {
                    validateEvent(input(e, null), "").also { skipped += "${e.title}: Wiederholungsregel ignoriert" }
                }
                ev.onSuccess { valid -> upsert(uid) { valid.copy(id = it) } }
                    .onFailure { skipped += "${e.title}: ${(it as? ApiException)?.serverMessage ?: it.message}" }
            }
            for (e in overrides) {
                val rid = e.recurrenceId!!
                val masterUid = e.uid.orEmpty()
                d = d.copy(events = d.events.orEmpty().map { m ->
                    if (m.calendarId != id || m.uid != masterUid || m.rrule == null || m.exdates.any { sameInstant(it, rid) }) m
                    else m.copy(exdates = (m.exdates + rid.toString()).sortedBy { parse(it) })
                })
                runCatching { validateEvent(input(e, null), "") }
                    .onSuccess { valid -> upsert("$masterUid#${rid.epochSecond}") { valid.copy(id = it) } }
                    .onFailure { skipped += "${e.title}: ${(it as? ApiException)?.serverMessage ?: it.message}" }
            }
            d
        }
        return ImportResult(imported, skipped)
    }

    private fun calendar(d: OfflineData, id: String) = d.calendars.orEmpty().firstOrNull { it.id == id } ?: notFound()

    private fun ensureWritable(cal: Calendar) {
        if (cal.isBirthdays) bad("Der Geburtstagskalender wird automatisch aus den Kontakten erstellt")
    }

    private fun validateCalendar(input: CalendarInput): Pair<String, String?> {
        val name = input.name.trim()
        if (name.isEmpty() || name.length > 100) bad("Der Name muss 1–100 Zeichen lang sein")
        val color = input.color.trim().takeIf { it.isNotEmpty() }?.let {
            if (!Regex("#[0-9A-Fa-f]{6}").matches(it)) bad("Farbe muss im Format #RRGGBB angegeben werden")
            it.uppercase()
        }
        return name to color
    }

    // ---------- Termine ----------

    override suspend fun occurrences(start: String, end: String, query: String?): List<Occurrence> {
        val from = parse(start) ?: bad("Ungültiger Zeitpunkt: $start")
        val to = parse(end) ?: bad("Ungültiger Zeitpunkt: $end")
        if (to <= from) bad("`end` muss nach `start` liegen")
        return data().occurrences(from, to, query).orEmpty()
    }

    override suspend fun event(id: String): CalEvent = data().events.orEmpty().firstOrNull { it.id == id } ?: notFound()

    override suspend fun createEvent(body: EventInput): CalEvent {
        val ev = validateEvent(body, newId()).copy(uid = newUid())
        edit { d ->
            ensureWritable(calendar(d, body.calendarId))
            d.copy(events = d.events.orEmpty() + ev)
        }
        return ev
    }

    override suspend fun updateEvent(id: String, body: EventInput): CalEvent {
        var result: CalEvent? = null
        edit { d ->
            val old = d.events.orEmpty().firstOrNull { it.id == id } ?: notFound()
            if (old.readOnly) bad(BIRTHDAY_READ_ONLY)
            ensureWritable(calendar(d, body.calendarId))
            val ev = validateEvent(body, id).copy(uid = old.uid)
            result = ev
            d.copy(events = d.events.orEmpty().map { if (it.id == id) ev else it })
        }
        return result!!
    }

    override suspend fun deleteEvent(id: String) {
        edit { d ->
            val old = d.events.orEmpty().firstOrNull { it.id == id } ?: notFound()
            if (old.readOnly) bad(BIRTHDAY_READ_ONLY)
            d.copy(events = d.events.orEmpty().filterNot { it.id == id })
        }
    }

    override suspend fun addExdate(id: String, body: ExdateRequest): CalEvent {
        val at = parse(body.occurrenceStart) ?: bad("Ungültiger Zeitpunkt: ${body.occurrenceStart}")
        var result: CalEvent? = null
        edit { d ->
            val old = d.events.orEmpty().firstOrNull { it.id == id } ?: notFound()
            if (old.readOnly) bad(BIRTHDAY_READ_ONLY)
            if (old.rrule.isNullOrBlank()) bad("Der Termin ist keine Serie")
            val ev = if (old.exdates.any { sameInstant(it, at) }) old
            else old.copy(exdates = (old.exdates + at.truncatedTo(ChronoUnit.SECONDS).toString()).sortedBy { parse(it) })
            result = ev
            d.copy(events = d.events.orEmpty().map { if (it.id == id) ev else it })
        }
        return result!!
    }

    /** Wie `EventData::validate` des Servers: liefert den gespeicherten Termin (ohne UID). */
    private fun validateEvent(input: EventInput, id: String): CalEvent {
        val title = input.title.trim()
        if (title.isEmpty() || title.length > 500) bad("Der Titel muss 1–500 Zeichen lang sein")
        val tzName = input.timezone.trim().ifEmpty { "UTC" }
        val zone = ICal.zoneOrNull(tzName) ?: bad("Unbekannte Zeitzone: $tzName")
        val s = parse(input.start) ?: bad("Ungültiger Beginn: ${input.start}")
        val e = parse(input.end) ?: bad("Ungültiges Ende: ${input.end}")
        val (start, end) = if (input.allDay) {
            // Ganztägig: Mitternacht UTC, Ende exklusiv
            val ds = midnightUtc(s)
            val de = midnightUtc(e)
            ds to if (de <= ds) ds.plus(Duration.ofDays(1)) else de
        } else {
            if (e < s) bad("Das Ende darf nicht vor dem Beginn liegen")
            s to e
        }
        val rrule = input.rrule?.trim()?.takeIf { it.isNotEmpty() }?.let { normalizeRrule(it, zone, input.allDay) }
        val exdates = if (rrule == null) emptyList() else
            input.exdates.mapNotNull(::parse).map { it.truncatedTo(ChronoUnit.SECONDS) }.distinct().sorted().map { it.toString() }
        return CalEvent(
            id = id, calendarId = input.calendarId, title = title, description = input.description.trimEnd(),
            location = input.location.trim(), start = start.toString(), end = end.toString(), allDay = input.allDay,
            timezone = if (zone.normalized() == ZoneOffset.UTC) "UTC" else zone.id, rrule = rrule, exdates = exdates,
        )
    }

    /** Großschreiben, `RRULE:` entfernen und UNTIL wie der Server in UTC umrechnen. */
    private fun normalizeRrule(rule: String, zone: ZoneId, allDay: Boolean): String {
        val parts = rule.removePrefix("RRULE:").removePrefix("rrule:").uppercase().split(';')
            .map { it.trim() }.filter { it.isNotEmpty() }
            .map { p -> if (p.startsWith("UNTIL=")) "UNTIL=" + normalizeUntil(p.removePrefix("UNTIL="), zone, allDay) else p }
        if (parts.isEmpty()) bad("Leere Wiederholungsregel")
        val normalized = parts.joinToString(";")
        if (!RRuleExpander.isSupported(normalized)) bad("Ungültige Wiederholungsregel: $normalized")
        return normalized
    }

    private fun normalizeUntil(v: String, zone: ZoneId, allDay: Boolean): String {
        if (v.endsWith("Z")) return v
        val utc = runCatching {
            val date = LocalDate.parse(v, DATE)
            // Ein reines Datum schließt den ganzen Tag ein
            if (allDay) date.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(86_399)
            else date.atTime(23, 59, 59).atZone(zone).toInstant()
        }.recoverCatching {
            val local = LocalDateTime.parse(v, DATE_TIME)
            if (allDay) local.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(86_399)
            else local.atZone(zone).toInstant()
        }.getOrNull() ?: return v // wird beim Prüfen abgelehnt
        return UNTIL_UTC.format(utc)
    }

    // ---------- Adressbücher ----------

    override suspend fun addressBooks(): List<AddressBook> {
        if (data().addressBooks.isNullOrEmpty()) ensureInitialized()
        return data().recount().addressBooks.orEmpty()
    }

    override suspend fun createAddressBook(body: AddressBookInput): AddressBook {
        val book = AddressBook(newId(), validateBookName(body.name), body.description.trim())
        edit { d -> d.copy(addressBooks = d.addressBooks.orEmpty() + book) }
        return book
    }

    override suspend fun updateAddressBook(id: String, body: AddressBookInput): AddressBook {
        val name = validateBookName(body.name)
        var result: AddressBook? = null
        edit { d ->
            val old = d.addressBooks.orEmpty().firstOrNull { it.id == id } ?: notFound()
            val book = old.copy(name = name, description = body.description.trim())
            result = book
            d.copy(addressBooks = d.addressBooks.orEmpty().map { if (it.id == id) book else it })
        }
        return result!!
    }

    override suspend fun deleteAddressBook(id: String) {
        val removed = data().contacts.orEmpty().filter { it.addressBookId == id }.map { it.id }
        edit { d ->
            d.addressBooks.orEmpty().firstOrNull { it.id == id } ?: notFound()
            val rest = d.addressBooks.orEmpty().filterNot { it.id == id }
            d.copy(
                addressBooks = rest.ifEmpty { listOf(AddressBook(newId(), DEFAULT_BOOK)) },
                contacts = d.contacts.orEmpty().filterNot { it.addressBookId == id },
            ).withBirthdays()
        }
        removed.forEach { store.savePhoto(it, "", null) }
    }

    override suspend fun exportAddressBook(id: String): ResponseBody {
        val d = data()
        d.addressBooks.orEmpty().firstOrNull { it.id == id } ?: notFound()
        val text = buildString {
            for (c in d.contacts.orEmpty().filter { it.addressBookId == id }) {
                append(VCard.create(c, if (c.hasPhoto) store.photo(c.id, c.etag) else null).serialize())
            }
        }
        return text.toResponseBody("text/vcard; charset=utf-8".toMediaType())
    }

    /** Wie `POST /addressbooks/{id}/import`: Kontakte mit gleicher UID werden aktualisiert. */
    override suspend fun importAddressBook(id: String, vcf: RequestBody): ContactImportResult {
        data().addressBooks.orEmpty().firstOrNull { it.id == id } ?: notFound()
        val cards = VCard.parseCards(vcf.readText())
        if (cards.isEmpty()) bad("Keine vCards gefunden (BEGIN:VCARD fehlt)")
        var created = 0
        var updated = 0
        var unchanged = 0
        val skipped = mutableListOf<String>()
        for (raw in cards) {
            val card = VCard.normalizeImport(raw)
            val parsed = VCard.extract(card)
            // Karten ohne UID erhalten eine aus dem Inhalt abgeleitete, damit ein erneuter Import keine Duplikate erzeugt
            val uid = parsed.uid.ifEmpty { stableUid(raw.serialize()) }
            val base = parsed.toInput().copy(
                addressBookId = id,
                // Ungültige Angaben verwerfen statt die ganze Karte
                birthday = parsed.birthday.takeIf { Birthdays.parse(it) != null }.orEmpty(),
                photo = parsed.photo?.takeIf { it.length <= MAX_PHOTO && it.startsWith("data:") } ?: "",
            ).normalizedInput()
            val input = base.copy(fullName = base.fullName.ifEmpty { "(Ohne Namen)" })
            if (uid.length > 500) {
                skipped += "${input.fullName}: UID zu lang"
                continue
            }
            val existing = data().contacts.orEmpty().firstOrNull { it.addressBookId == id && it.uid == uid }
            try {
                if (existing == null) {
                    save(newId(), uid, input, previous = null)
                    created++
                } else {
                    val sameData = existing.toInput() == input.copy(photo = null)
                    val samePhoto = (if (existing.hasPhoto) store.photo(existing.id, existing.etag) else null).orEmpty() == input.photo
                    if (sameData && samePhoto) unchanged++
                    else {
                        save(existing.id, uid, input, previous = existing)
                        updated++
                    }
                }
            } catch (e: ApiException) {
                skipped += "${input.fullName}: ${e.serverMessage}"
            }
        }
        return ContactImportResult(created + updated + unchanged, created, updated, unchanged, skipped)
    }

    private fun validateBookName(name: String): String {
        val n = name.trim()
        if (n.isEmpty() || n.length > 100) bad("Der Name muss 1–100 Zeichen lang sein")
        return n
    }

    // ---------- Kontakte ----------

    override suspend fun contacts(addressBookId: String?): List<Contact> =
        data().contacts.orEmpty().filter { addressBookId == null || it.addressBookId == addressBookId }

    override suspend fun contact(id: String): Contact {
        val c = data().contacts.orEmpty().firstOrNull { it.id == id } ?: notFound()
        return c.copy(photo = if (c.hasPhoto) store.photo(c.id, c.etag) else null)
    }

    override suspend fun createContact(body: ContactInput): Contact {
        data().addressBooks.orEmpty().firstOrNull { it.id == body.addressBookId } ?: bad("Adressbuch nicht gefunden")
        return save(newId(), UUID.randomUUID().toString(), body, previous = null)
    }

    override suspend fun updateContact(id: String, body: ContactInput): Contact {
        val old = data().contacts.orEmpty().firstOrNull { it.id == id } ?: notFound()
        data().addressBooks.orEmpty().firstOrNull { it.id == body.addressBookId } ?: bad("Adressbuch nicht gefunden")
        return save(id, old.uid, body, previous = old)
    }

    override suspend fun deleteContact(id: String) {
        edit { d ->
            d.contacts.orEmpty().firstOrNull { it.id == id } ?: notFound()
            d.copy(contacts = d.contacts.orEmpty().filterNot { it.id == id }).withBirthdays()
        }
        store.savePhoto(id, "", null)
    }

    /** Prüft und speichert einen Kontakt samt Foto (`null` = unverändert, `""` = entfernen). */
    private suspend fun save(id: String, uid: String, body: ContactInput, previous: Contact?): Contact {
        val input = body.normalizedInput()
        validateContact(input)
        val photo = when (body.photo) {
            null -> previous?.takeIf { it.hasPhoto }?.let { store.photo(it.id, it.etag) }
            "" -> null
            else -> body.photo.also(::validatePhoto)
        }
        val contact = Contact(
            id = id, addressBookId = input.addressBookId, uid = uid, etag = UUID.randomUUID().toString(),
            fullName = input.fullName, prefix = input.prefix, givenName = input.givenName,
            additionalNames = input.additionalNames, familyName = input.familyName, suffix = input.suffix,
            nickname = input.nickname, organization = input.organization, department = input.department,
            title = input.title, phones = input.phones, emails = input.emails, addresses = input.addresses,
            urls = input.urls, birthday = input.birthday, note = input.note, hasPhoto = photo != null,
        )
        // Foto zuerst, damit es zum neuen ETag schon da ist, wenn die Liste ihn zeigt
        store.savePhoto(id, contact.etag, photo)
        edit { d ->
            val list = d.contacts.orEmpty()
            val next = if (list.any { it.id == id }) list.map { if (it.id == id) contact else it } else list + contact
            d.copy(contacts = next).withBirthdays().recount()
        }
        return contact.copy(photo = photo)
    }

    private fun validateContact(c: ContactInput) {
        if (c.fullName.isEmpty()) bad("Bitte einen Namen, eine Firma, E-Mail-Adresse oder Telefonnummer angeben")
        if (c.fullName.length > 500 || c.note.length > 20_000) bad("Name oder Notiz ist zu lang")
        if (c.birthday.isNotEmpty() && Birthdays.parse(c.birthday) == null) bad("Ungültiger Geburtstag")
    }

    private fun validatePhoto(p: String) {
        if (p.length > MAX_PHOTO) bad("Das Foto ist zu groß (max. 3,5 MB)")
        if (!p.startsWith("data:") || ',' !in p) bad("Das Foto muss als data:-URL (Base64) übergeben werden")
    }

    private fun OfflineData.withBirthdays() = Birthdays.sync(this, ::newId, now())

    // ---------- Hilfen ----------

    private suspend fun RequestBody.readText(): String = Buffer().also { writeTo(it) }.readUtf8()

    companion object {
        private const val DEFAULT_BOOK = "Kontakte"
        private const val BIRTHDAY_READ_ONLY = "Geburtstage werden über den Kontakt bearbeitet"
        private const val MAX_PHOTO = 5_000_000
        private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
        private val UNTIL_UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

        fun newId(): String = UUID.randomUUID().toString()
        private fun newUid() = "${UUID.randomUUID()}@synchaven"
        private fun now(): String = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()

        fun parse(s: String): Instant? = runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull()
        private fun sameInstant(s: String, t: Instant) = parse(s) == t
        private fun midnightUtc(t: Instant): Instant = t.atOffset(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant()

        private fun bad(message: String): Nothing = throw ApiException(400, "bad_request", message)
        private fun notFound(): Nothing = throw ApiException(404, "not_found", "Nicht gefunden")
        private fun unavailable(): Nothing = throw ApiException(400, "local_mode", "Im lokalen Modus nicht verfügbar")

        /** UID im UUID-Format aus dem Inhalt einer Karte */
        private fun stableUid(text: String): String {
            val h = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
            return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20, 32)}"
        }

        /** Wie `ContactData::normalize` des Servers: Leerraum entfernen, leere Einträge weglassen, Namen ableiten. */
        fun ContactInput.normalizedInput(): ContactInput {
            fun known(t: String): String {
                val lower = t.trim().lowercase()
                return if (lower.isEmpty() || lower in listOf("mobile", "home", "work", "fax", "pager", "other")) lower else t.trim()
            }
            val n = copy(
                prefix = prefix.trim(), givenName = givenName.trim(), additionalNames = additionalNames.trim(),
                familyName = familyName.trim(), suffix = suffix.trim(), nickname = nickname.trim(),
                organization = organization.trim(), department = department.trim(), title = title.trim(),
                birthday = birthday.trim(), note = note.trimEnd(), fullName = fullName.trim(),
                phones = phones.filter { it.value.isNotBlank() }.map { it.copy(type = known(it.type), value = it.value.trim()) },
                emails = emails.filter { it.value.isNotBlank() }.map { it.copy(type = known(it.type), value = it.value.trim()) },
                urls = urls.filter { it.value.isNotBlank() }.map { it.copy(type = known(it.type), value = it.value.trim()) },
                addresses = addresses.filter { a ->
                    listOf(a.poBox, a.extended, a.street, a.locality, a.region, a.postalCode, a.country).any { it.isNotBlank() }
                }.map { it.copy(type = known(it.type)) },
            )
            if (n.fullName.isNotEmpty()) return n
            val name = listOf(n.prefix, n.givenName, n.additionalNames, n.familyName, n.suffix)
                .filter { it.isNotEmpty() }.joinToString(" ")
            val derived = (listOf(name, n.organization) + n.emails.map { it.value } + n.phones.map { it.value })
                .firstOrNull { it.isNotEmpty() }.orEmpty()
            return n.copy(fullName = derived)
        }
    }
}
