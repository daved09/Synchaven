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
import de.dave.synchaven.data.withTaskCompleted
import de.dave.synchaven.data.withTaskMoved
import de.dave.synchaven.data.withTaskSaved
import de.dave.synchaven.data.withoutTask
import de.dave.synchaven.util.RRuleExpander
import de.dave.synchaven.util.Reminders
import de.dave.synchaven.util.applyReminders
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.serialization.Serializable

/**
 * Local state of an account: mirror of the server data with changes not yet transferred
 * already applied ([outbox]). `null` for a list means "never loaded".
 */
@Serializable
data class OfflineData(
    val calendars: List<Calendar>? = null,
    /** All events of all calendars, series as one entry */
    val events: List<CalEvent>? = null,
    val addressBooks: List<AddressBook>? = null,
    /** Contacts without photos (those are stored separately in the [OfflineStore]) */
    val contacts: List<Contact>? = null,
    /** Task lists; stays `null` in local mode and with servers without tasks */
    val taskLists: List<TaskList>? = null,
    /** All tasks of all lists */
    val tasks: List<Task>? = null,
    val outbox: List<PendingOp> = emptyList(),
    /** Temporary ID → server ID of creations already transferred (for screens that are still open) */
    val idMap: Map<String, String> = emptyMap(),
    /** Last complete sync with the server (RFC 3339) */
    val lastSync: String? = null,
    /** Version of the data in local mode, see [migrateLocal] */
    val version: Int = 0,
) {
    /**
     * Brings local mode data up to date (on the server, the migrations take care of this).
     * 1: calendars without default reminders get the preset (15 minutes before, all-day 09:00) –
     *    an early test version had stored empty lists.
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

    /** Applies a change locally. Idempotent, so it can be applied again after every load from the server. */
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
            val base = list.firstOrNull { it.id == op.localId }
            list.withTaskSaved(op.input.toLocalTask(op.localId, base = base, createdAt = op.createdAt), place = true) { t, done ->
                t.withCompleted(done, op.createdAt)
            }
        }
        is PendingOp.UpdateTask -> withTasks { list ->
            val base = list.firstOrNull { it.id == op.id } ?: return@withTasks list
            list.withTaskSaved(op.input.toLocalTask(base.id, base = base), place = true) { t, done -> t.withCompleted(done, "") }
        }
        is PendingOp.MoveTask -> withTasks { list -> list.withTaskMoved(op.id, op.after) }
        is PendingOp.SetTaskCompleted -> withTasks { list ->
            list.withTaskCompleted(op.id, op.completed, op.at) { t, done -> t.withCompleted(done, op.at) }
        }
        is PendingOp.DeleteTask -> withTasks { list -> list.withoutTask(op.id) }
        is PendingOp.UpdateTaskList -> copy(taskLists = taskLists?.map { l ->
            if (l.id == op.id) {
                l.copy(
                    name = op.input.name, color = op.input.color ?: l.color, visible = op.input.visible ?: l.visible,
                    sortBy = op.input.sortBy ?: l.sortBy,
                )
            } else {
                l
            }
        })
        is PendingOp.SetDefaultView -> this
    }

    /** Applies all pending changes, e.g. after data has been reloaded from the server. */
    fun withPending(): OfflineData = outbox.fold(this) { d, op -> d.apply(op) }.recount()

    /**
     * Number of contacts per address book and of open tasks per list, computed from the entries – so it is also correct
     * after offline changes
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
     * Occurrences in the range [from, to), as `GET /events` would return them. Birthdays get the age from the
     * contact's birth year. `null` as long as the events have never been loaded.
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

/** Current version of the data in local mode ([OfflineData.migrateLocal]) */
const val LOCAL_VERSION = 1

/** Birth year from `YYYY-MM-DD`; unknown for `--MM-DD` */
private fun birthYear(birthday: String): Int? =
    Regex("""^(\d{4})-\d{2}-\d{2}$""").matchEntire(birthday)?.groupValues?.get(1)?.toIntOrNull()

internal fun parseOrNull(s: String): Instant? = runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull()

private fun sameInstant(a: String, b: String) = a == b || (parseOrNull(a) != null && parseOrNull(a) == parseOrNull(b))

/** Event as the server would return it after saving; [base] provides the fields the input doesn't have. */
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
 * Task as the server would return it after saving: without a date always all-day, all-day at midnight
 * UTC of the day. If `completed` is missing in the input, the status of [base] is kept.
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
        parentId = parentId ?: base?.parentId,
        title = title.trim(),
        notes = notes.trimEnd(),
        due = dueAt,
        allDay = allDay,
        completed = done,
        completedAt = if (done) base?.completedAt?.takeIf { base.completed } ?: createdAt.ifEmpty { null } else null,
        createdAt = base?.createdAt ?: createdAt,
        updatedAt = base?.updatedAt ?: createdAt,
        position = base?.position ?: 0,
    )
}

/** Checked off or reopened; the completion time is kept as long as the task stays completed. */
fun Task.withCompleted(done: Boolean, at: String): Task = copy(
    completed = done,
    completedAt = if (!done) null else completedAt?.takeIf { completed } ?: at.ifEmpty { null },
)

/** Contact (without photo) as the server would return it after saving */
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
