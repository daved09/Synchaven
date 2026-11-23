package de.dave.synchaven.data.offline

import de.dave.synchaven.data.model.AddressBook
import de.dave.synchaven.data.model.CalEvent
import de.dave.synchaven.data.model.Calendar
import de.dave.synchaven.data.model.Contact
import de.dave.synchaven.data.model.ContactInput
import de.dave.synchaven.data.model.EventInput
import de.dave.synchaven.data.model.Occurrence
import de.dave.synchaven.data.model.Task
import de.dave.synchaven.data.model.TaskInput
import de.dave.synchaven.data.model.TaskList
import de.dave.synchaven.util.RRuleExpander
import de.dave.synchaven.util.Reminders
import de.dave.synchaven.util.applyReminders
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.serialization.Serializable

/**
 * Lokaler Stand eines Kontos: Spiegel der Serverdaten mit bereits eingerechneten, noch nicht übertragenen
 * Änderungen ([outbox]). `null` bei einer Liste heißt „noch nie geladen“.
 */
@Serializable
data class OfflineData(
    val calendars: List<Calendar>? = null,
    /** Alle Termine aller Kalender, Serien als ein Eintrag */
    val events: List<CalEvent>? = null,
    val addressBooks: List<AddressBook>? = null,
    /** Kontakte ohne Fotos (die liegen getrennt im [OfflineStore]) */
    val contacts: List<Contact>? = null,
    /** Aufgabenlisten; bleibt `null` im lokalen Modus und bei Servern ohne Aufgaben */
    val taskLists: List<TaskList>? = null,
    /** Alle Aufgaben aller Listen */
    val tasks: List<Task>? = null,
    val outbox: List<PendingOp> = emptyList(),
    /** Vorläufige ID → Server-ID bereits übertragener Anlagen (für noch geöffnete Bildschirme) */
    val idMap: Map<String, String> = emptyMap(),
    /** Letzter vollständiger Abgleich mit dem Server (RFC 3339) */
    val lastSync: String? = null,
    /** Stand der Daten im lokalen Modus, siehe [migrateLocal] */
    val version: Int = 0,
) {
    /**
     * Bringt Daten des lokalen Modus auf den aktuellen Stand (beim Server übernehmen das die Migrationen).
     * 1: Kalender ohne Standard-Erinnerungen erhalten die Voreinstellung (15 Minuten vorher, ganztägig 09:00) –
     *    eine frühe Testversion hatte leere Listen gespeichert.
     */
    fun migrateLocal(): OfflineData {
        if (version >= LOCAL_VERSION) return this
        return copy(
            calendars = calendars?.map { c ->
                if (c.defaultReminders.isEmpty() && c.defaultAllDayReminders.isEmpty()) {
                    c.copy(defaultReminders = Reminders.DEFAULT_TIMED, defaultAllDayReminders = Reminders.DEFAULT_ALL_DAY)
                } else c
            },
            version = LOCAL_VERSION,
        )
    }

    fun resolve(id: String): String = idMap[id] ?: id

    /** Wendet eine Änderung lokal an. Idempotent, damit sie nach jedem Laden vom Server erneut angewendet werden kann. */
    fun apply(op: PendingOp): OfflineData = when (op) {
        is PendingOp.CreateEvent -> withEvents { list ->
            val ev = op.input.toLocalEvent(op.localId)
            if (list.any { it.id == op.localId }) list.map { if (it.id == op.localId) ev else it } else list + ev
        }
        is PendingOp.UpdateEvent -> withEvents { list ->
            list.map { if (it.id == op.id) op.input.toLocalEvent(it.id, base = it) else it }
        }
        is PendingOp.DeleteEvent -> withEvents { list -> list.filterNot { it.id == op.id } }
        is PendingOp.AddExdate -> withEvents { list ->
            list.map { ev ->
                if (ev.id != op.id || ev.exdates.any { sameInstant(it, op.occurrenceStart) }) ev
                else ev.copy(exdates = ev.exdates + op.occurrenceStart)
            }
        }
        is PendingOp.CreateContact -> withContacts { list ->
            val c = op.input.toLocalContact(op.localId)
            if (list.any { it.id == op.localId }) list.map { if (it.id == op.localId) c else it } else list + c
        }
        is PendingOp.UpdateContact -> withContacts { list ->
            list.map { if (it.id == op.id) op.input.toLocalContact(it.id, base = it) else it }
        }
        is PendingOp.DeleteContact -> withContacts { list -> list.filterNot { it.id == op.id } }
        is PendingOp.UpdateCalendar -> copy(calendars = calendars?.map { c ->
            if (c.id == op.id) c.copy(name = op.input.name, color = op.input.color, description = op.input.description,
                visible = op.input.visible,
                defaultReminders = op.input.defaultReminders ?: c.defaultReminders,
                defaultAllDayReminders = op.input.defaultAllDayReminders ?: c.defaultAllDayReminders) else c
        })
        is PendingOp.CreateTask -> withTasks { list ->
            val t = op.input.toLocalTask(op.localId, createdAt = op.createdAt)
            if (list.any { it.id == op.localId }) list.map { if (it.id == op.localId) t else it } else list + t
        }
        is PendingOp.UpdateTask -> withTasks { list ->
            list.map { if (it.id == op.id) op.input.toLocalTask(it.id, base = it) else it }
        }
        is PendingOp.SetTaskCompleted -> withTasks { list ->
            list.map { if (it.id == op.id) it.withCompleted(op.completed, op.at) else it }
        }
        is PendingOp.DeleteTask -> withTasks { list -> list.filterNot { it.id == op.id } }
        is PendingOp.UpdateTaskList -> copy(taskLists = taskLists?.map { l ->
            if (l.id == op.id) l.copy(name = op.input.name, color = op.input.color ?: l.color, visible = op.input.visible ?: l.visible)
            else l
        })
        is PendingOp.SetDefaultView -> this
    }

    /** Rechnet alle ausstehenden Änderungen ein, z. B. nachdem Daten vom Server neu geladen wurden. */
    fun withPending(): OfflineData = outbox.fold(this) { d, op -> d.apply(op) }.recount()

    /**
     * Anzahl der Kontakte je Adressbuch und der offenen Aufgaben je Liste aus den Einträgen – stimmt so auch nach
     * Offline-Änderungen
     */
    fun recount(): OfflineData {
        var d = this
        contacts?.let { list ->
            val counts = list.groupingBy { it.addressBookId }.eachCount()
            d = d.copy(addressBooks = d.addressBooks?.map { it.copy(contactCount = counts[it.id] ?: 0) })
        }
        tasks?.let { list ->
            val open = list.filterNot { it.completed }.groupingBy { it.listId }.eachCount()
            d = d.copy(taskLists = d.taskLists?.map { it.copy(openCount = open[it.id] ?: 0) })
        }
        return d
    }

    private fun withEvents(f: (List<CalEvent>) -> List<CalEvent>) = copy(events = f(events.orEmpty()))
    private fun withContacts(f: (List<Contact>) -> List<Contact>) = copy(contacts = f(contacts.orEmpty()))
    private fun withTasks(f: (List<Task>) -> List<Task>) = copy(tasks = f(tasks.orEmpty()))

    /**
     * Vorkommen im Zeitraum [from, to), wie sie `GET /events` liefern würde. Geburtstage erhalten das Alter aus dem
     * Geburtsjahr des Kontakts. `null`, solange die Termine noch nie geladen wurden.
     */
    fun occurrences(from: Instant, to: Instant, query: String? = null): List<Occurrence>? {
        val all = events ?: return null
        val birthYears = contacts.orEmpty().associate { it.id to birthYear(it.birthday) }
        val q = query?.trim()?.takeIf { it.isNotEmpty() }
        return all.asSequence()
            .filter { q == null || listOf(it.title, it.description, it.location).any { s -> s.contains(q, ignoreCase = true) } }
            .flatMap { ev -> ev.expand(from, to, ev.contactId?.let { birthYears[it] }) }
            .sortedWith(compareBy<Occurrence>({ parseOrNull(it.start) }, { !it.allDay }, { it.title }))
            .toList()
    }
}

private fun CalEvent.expand(from: Instant, to: Instant, birthYear: Int?): List<Occurrence> {
    val start = parseOrNull(start) ?: return emptyList()
    val end = parseOrNull(end) ?: start
    val zone = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneOffset.UTC)
    val recurring = !rrule.isNullOrBlank()
    val exdates = exdates.mapNotNull { parseOrNull(it) }.toSet()
    val duration = java.time.Duration.between(start, end)
    return RRuleExpander.occurrences(start, end, zone, allDay, rrule, exdates, from, to).map { s ->
        val year = s.atOffset(ZoneOffset.UTC).year
        val age = birthYear?.let { year - it }
        Occurrence(
            id = if (recurring) "${id}_${s.epochSecond}" else id,
            eventId = id,
            calendarId = calendarId,
            title = if (contactId != null && age != null && age > 0) "$title ($age)" else title,
            description = description,
            location = location,
            start = s.toString(),
            end = s.plus(duration).toString(),
            allDay = allDay,
            timezone = timezone,
            recurring = recurring,
            rrule = rrule,
            contactId = contactId,
            readOnly = readOnly,
            reminders = reminders,
        )
    }
}

/** Aktueller Stand der Daten im lokalen Modus ([OfflineData.migrateLocal]) */
const val LOCAL_VERSION = 1

/** Geburtsjahr aus `YYYY-MM-DD`; bei `--MM-DD` unbekannt */
private fun birthYear(birthday: String): Int? =
    Regex("""^(\d{4})-\d{2}-\d{2}$""").matchEntire(birthday)?.groupValues?.get(1)?.toIntOrNull()

internal fun parseOrNull(s: String): Instant? = runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull()

private fun sameInstant(a: String, b: String) = a == b || (parseOrNull(a) != null && parseOrNull(a) == parseOrNull(b))

/** Termin, wie ihn der Server nach dem Speichern liefern würde; [base] liefert die Felder, die die Eingabe nicht hat. */
fun EventInput.toLocalEvent(id: String, base: CalEvent? = null) = CalEvent(
    id = id,
    calendarId = calendarId,
    uid = base?.uid.orEmpty(),
    title = title,
    description = description,
    location = location,
    start = start,
    end = end,
    allDay = allDay,
    timezone = timezone,
    rrule = rrule?.takeIf { it.isNotBlank() },
    exdates = exdates,
    reminders = applyReminders(base?.reminders),
    contactId = base?.contactId,
    readOnly = base?.readOnly ?: false,
)

/**
 * Aufgabe, wie sie der Server nach dem Speichern liefern würde: ohne Datum immer ganztägig, ganztägig auf Mitternacht
 * UTC des Tages. Fehlt `completed` in der Eingabe, bleibt der Status von [base] erhalten.
 */
fun TaskInput.toLocalTask(id: String, base: Task? = null, createdAt: String = ""): Task {
    val allDay = allDay || due == null
    val dueAt = due?.let { d ->
        if (!allDay) d
        else parseOrNull(d)?.atOffset(ZoneOffset.UTC)?.toLocalDate()?.let { "${it}T00:00:00Z" } ?: d
    }
    val done = completed ?: base?.completed ?: false
    return Task(
        id = id,
        listId = listId,
        title = title.trim(),
        notes = notes.trimEnd(),
        due = dueAt,
        allDay = allDay,
        completed = done,
        completedAt = if (done) base?.completedAt?.takeIf { base.completed } ?: createdAt.ifEmpty { null } else null,
        createdAt = base?.createdAt ?: createdAt,
        updatedAt = base?.updatedAt ?: createdAt,
    )
}

/** Abgehakt bzw. wieder geöffnet; der Erledigungszeitpunkt bleibt erhalten, solange die Aufgabe erledigt bleibt. */
fun Task.withCompleted(done: Boolean, at: String): Task = copy(
    completed = done,
    completedAt = if (!done) null else completedAt?.takeIf { completed } ?: at.ifEmpty { null },
)

/** Kontakt (ohne Foto), wie ihn der Server nach dem Speichern liefern würde */
fun ContactInput.toLocalContact(id: String, base: Contact? = null) = Contact(
    id = id,
    addressBookId = addressBookId,
    uid = base?.uid.orEmpty(),
    etag = base?.etag.orEmpty(),
    fullName = fullName,
    prefix = prefix,
    givenName = givenName,
    additionalNames = additionalNames,
    familyName = familyName,
    suffix = suffix,
    nickname = nickname,
    organization = organization,
    department = department,
    title = title,
    phones = phones,
    emails = emails,
    addresses = addresses,
    urls = urls,
    birthday = birthday,
    note = note,
    categories = categories ?: base?.categories.orEmpty(),
    hasPhoto = when (photo) {
        null -> base?.hasPhoto ?: false
        else -> photo.isNotEmpty()
    },
    photo = null,
)
