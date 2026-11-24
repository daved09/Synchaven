package de.dave.synchaven.data.local

import de.dave.synchaven.data.api.ApiException
import de.dave.synchaven.util.Reminders
import de.dave.synchaven.util.applyReminders
import de.dave.synchaven.util.cleanCategories
import de.dave.synchaven.util.effectiveReminders
import de.dave.synchaven.data.api.NoServerConfiguredException
import de.dave.synchaven.data.api.SyncHavenApi
import de.dave.synchaven.data.model.AddressBook
import de.dave.synchaven.data.model.AddressBookInput
import de.dave.synchaven.data.model.AuthResponse
import de.dave.synchaven.data.model.CalEvent
import de.dave.synchaven.data.model.Calendar
import de.dave.synchaven.data.model.CalendarInput
import de.dave.synchaven.data.model.ClearCompletedResult
import de.dave.synchaven.data.model.CompletedRequest
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
import de.dave.synchaven.data.model.Task
import de.dave.synchaven.data.model.TaskInput
import de.dave.synchaven.data.model.TaskList
import de.dave.synchaven.data.model.TaskListInput
import de.dave.synchaven.data.model.UpdateMeRequest
import de.dave.synchaven.data.model.User
import de.dave.synchaven.data.offline.OfflineData
import de.dave.synchaven.data.offline.OfflineStore
import de.dave.synchaven.data.offline.toLocalTask
import de.dave.synchaven.data.withTaskCompleted
import de.dave.synchaven.data.withTaskSaved
import de.dave.synchaven.data.withoutCompleted
import de.dave.synchaven.data.withoutTask
import de.dave.synchaven.data.offline.withCompleted
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

/** Key of the [OfflineStore] for the local mode's data */
const val LOCAL_ACCOUNT = "local"

/** Default list as on the server ("Meine Aufgaben") */
private const val DEFAULT_TASK_LIST = "Meine Aufgaben"
private const val DEFAULT_TASK_COLOR = "#1A73E8"

/** Creates the default task list if there is none yet. */
private fun OfflineData.withDefaultTaskList(ts: String): OfflineData =
    if (!taskLists.isNullOrEmpty()) this
    else copy(taskLists = listOf(TaskList(LocalApi.newId(), DEFAULT_TASK_LIST, DEFAULT_TASK_COLOR, createdAt = ts, updatedAt = ts)))

/**
 * "Server" of the local mode: the same interface as the REST API, but stored in the [OfflineStore] under
 * [LOCAL_ACCOUNT]. This way the repositories and the sync with Android work unchanged, even entirely without
 * the web UI. Validation and error messages match those of the server (see backend/src/routes).
 */
class LocalApi(
    private val store: OfflineStore,
    /** Default view from the session (for [me]) */
    private val defaultView: () -> String,
) : SyncHavenApi {

    // ---------- Storage ----------

    private suspend fun data(): OfflineData {
        store.awaitReady()
        if (store.currentAccount != LOCAL_ACCOUNT) throw NoServerConfiguredException()
        return store.data.value
    }

    /** Changes the local data atomically; [transform] may abort with [bad]/[notFound]. */
    private suspend fun edit(transform: (OfflineData) -> OfflineData): OfflineData {
        data()
        return store.update(transform)
    }

    /** Creates a default calendar and an address book when first opened (like registration on the server). */
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
                tasks = d.tasks ?: emptyList(),
            ).withDefaultTaskList(ts).migrateLocal().let { Birthdays.sync(it, ::newId, ts) }.recount()
        }
    }

    // ---------- Account ----------

    override suspend fun info(server: String): ServerInfo = localInfo()
    override suspend fun currentInfo(): ServerInfo = localInfo()
    override suspend fun login(server: String, body: LoginRequest): AuthResponse = unavailable()
    override suspend fun register(server: String, body: RegisterRequest): AuthResponse = unavailable()
    override suspend fun refreshToken(): AuthResponse = unavailable()
    override suspend fun me(): User = User(id = LOCAL_ACCOUNT, email = "", defaultView = defaultView())

    /** Locally there is only the default view; the session stores it. */
    override suspend fun updateMe(body: UpdateMeRequest): User {
        if (body.name != null || body.email != null || body.newPassword != null) unavailable()
        return me().let { u -> body.defaultView?.let { u.copy(defaultView = it) } ?: u }
    }

    private fun localInfo() = ServerInfo(name = "Synchaven (lokal)")

    // ---------- Tasks ----------
    // Like backend/src/routes/tasks.rs: at least one list, the last one cannot be deleted.

    override suspend fun taskLists(): List<TaskList> {
        if (data().taskLists.isNullOrEmpty()) edit { it.withDefaultTaskList(now()) }
        return data().taskLists.orEmpty()
    }

    override suspend fun createTaskList(body: TaskListInput): TaskList {
        val (name, color) = validateTaskList(body)
        val ts = now()
        val list = TaskList(newId(), name, color ?: DEFAULT_TASK_COLOR, body.visible ?: true, createdAt = ts, updatedAt = ts)
        edit { d -> d.copy(taskLists = d.taskLists.orEmpty() + list).recount() }
        return data().taskLists.orEmpty().first { it.id == list.id }
    }

    override suspend fun updateTaskList(id: String, body: TaskListInput): TaskList {
        val (name, color) = validateTaskList(body)
        edit { d ->
            val old = taskList(d, id)
            val list = old.copy(name = name, color = color ?: old.color, visible = body.visible ?: old.visible, updatedAt = now())
            d.copy(taskLists = d.taskLists.orEmpty().map { if (it.id == id) list else it })
        }
        return taskList(data(), id)
    }

    override suspend fun deleteTaskList(id: String) {
        edit { d ->
            taskList(d, id)
            if (d.taskLists.orEmpty().size <= 1) bad("Die letzte Aufgabenliste kann nicht gelöscht werden")
            d.copy(taskLists = d.taskLists.orEmpty().filterNot { it.id == id }, tasks = d.tasks.orEmpty().filterNot { it.listId == id })
        }
    }

    override suspend fun clearCompletedTasks(id: String): ClearCompletedResult {
        var deleted = 0
        edit { d ->
            taskList(d, id)
            val all = d.tasks.orEmpty()
            val keep = all.withoutCompleted(id)
            deleted = all.size - keep.size
            d.copy(tasks = keep).recount()
        }
        return ClearCompletedResult(deleted)
    }

    /** Open before completed, then by due date (without date last) – like `GET /tasks`. */
    override suspend fun tasks(listId: String?): List<Task> = data().tasks.orEmpty()
        .filter { listId == null || it.listId == listId }
        .sortedWith(compareBy<Task>({ it.completed }, { it.due == null }, { it.due?.let(::parse) }, { it.createdAt }, { it.id }))

    override suspend fun task(id: String): Task = task(data(), id)

    override suspend fun createTask(body: TaskInput): Task {
        validateTask(body)
        val ts = now()
        val t = body.toLocalTask(newId(), createdAt = ts)
        edit { d ->
            taskList(d, t.listId)
            validateParent(d, null, body)
            d.copy(tasks = d.tasks.orEmpty().withTaskSaved(t) { x, done -> x.withCompleted(done, ts) }).recount()
        }
        return task(data(), t.id)
    }

    override suspend fun updateTask(id: String, body: TaskInput): Task {
        validateTask(body)
        edit { d ->
            val old = task(d, id)
            taskList(d, body.listId)
            val ts = now()
            validateParent(d, old, body)
            val t = body.toLocalTask(id, base = old, createdAt = ts).copy(updatedAt = ts)
            d.copy(tasks = d.tasks.orEmpty().withTaskSaved(t) { x, done -> x.withCompleted(done, ts).copy(updatedAt = ts) }).recount()
        }
        return task(data(), id)
    }

    override suspend fun setTaskCompleted(id: String, body: CompletedRequest): Task {
        edit { d ->
            val ts = now()
            task(d, id)
            d.copy(
                tasks = d.tasks.orEmpty().withTaskCompleted(id, body.completed, ts) { t, done ->
                    t.withCompleted(done, ts).copy(updatedAt = ts)
                },
            ).recount()
        }
        return task(data(), id)
    }

    override suspend fun deleteTask(id: String) {
        edit { d ->
            task(d, id)
            d.copy(tasks = d.tasks.orEmpty().withoutTask(id)).recount()
        }
    }

    private fun taskList(d: OfflineData, id: String) = d.taskLists.orEmpty().firstOrNull { it.id == id } ?: notFound()
    private fun task(d: OfflineData, id: String) = d.tasks.orEmpty().firstOrNull { it.id == id } ?: notFound()

    private fun validateTaskList(input: TaskListInput): Pair<String, String?> {
        val name = input.name.trim()
        if (name.isEmpty() || name.length > 100) bad("Der Name muss 1–100 Zeichen lang sein")
        val color = input.color?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (!Regex("#[0-9A-Fa-f]{6}").matches(it)) bad("Farbe muss im Format #RRGGBB angegeben werden")
            it.uppercase()
        }
        return name to color
    }

    /** Like `resolve_parent` in the backend: one level deep, the parent task must exist. */
    private fun validateParent(d: OfflineData, existing: Task?, input: TaskInput) {
        val parentId = input.parentId ?: return
        if (existing?.id == parentId) bad("Eine Aufgabe kann nicht ihre eigene Unteraufgabe sein")
        val parent = d.tasks.orEmpty().firstOrNull { it.id == parentId } ?: bad("Die übergeordnete Aufgabe gibt es nicht")
        if (parent.parentId != null) bad("Unteraufgaben können keine eigenen Unteraufgaben haben")
        if (parent.listId == input.listId && existing != null && d.tasks.orEmpty().any { it.parentId == existing.id }) {
            bad("Eine Aufgabe mit Unteraufgaben kann keine Unteraufgabe werden")
        }
    }

    private fun validateTask(input: TaskInput) {
        val title = input.title.trim()
        if (title.isEmpty() || title.length > 500) bad("Der Titel muss 1–500 Zeichen lang sein")
        if (input.due != null && parse(input.due) == null) bad("Ungültige Fälligkeit")
    }

    // ---------- Calendars ----------

    override suspend fun calendars(): List<Calendar> = data().calendars.orEmpty()

    override suspend fun createCalendar(body: CalendarInput): Calendar {
        val (name, color) = validateCalendar(body)
        val ts = now()
        val cal = Calendar(
            newId(), name, color ?: "#6750A4", body.description.trim(), body.visible,
            defaultReminders = validReminders(body.defaultReminders ?: Reminders.DEFAULT_TIMED, false),
            defaultAllDayReminders = validReminders(body.defaultAllDayReminders ?: Reminders.DEFAULT_ALL_DAY, true),
            createdAt = ts, updatedAt = ts,
        )
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
                defaultReminders = body.defaultReminders?.let { validReminders(it, false) } ?: old.defaultReminders,
                defaultAllDayReminders = body.defaultAllDayReminders?.let { validReminders(it, true) } ?: old.defaultAllDayReminders,
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
        return ICal.export(cal.name, events, reminders = { it.effectiveReminders(cal) }).toResponseBody("text/calendar; charset=utf-8".toMediaType())
    }

    /** Like `POST /calendars/{id}/import`: events with the same UID are updated, modified single occurrences
     *  of a series become separate events and are excluded from the series. */
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
                // An invalid RRULE should not discard the whole event
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

    // ---------- Events ----------

    override suspend fun occurrences(start: String, end: String, query: String?): List<Occurrence> {
        val from = parse(start) ?: bad("Ungültiger Zeitpunkt: $start")
        val to = parse(end) ?: bad("Ungültiger Zeitpunkt: $end")
        if (to <= from) bad("`end` muss nach `start` liegen")
        return data().occurrences(from, to, query).orEmpty()
    }

    override suspend fun event(id: String): CalEvent = data().events.orEmpty().firstOrNull { it.id == id } ?: notFound()

    override suspend fun createEvent(body: EventInput): CalEvent {
        val ev = validateEvent(body, newId()).let { it.copy(uid = newUid(), reminders = body.applyReminders(null)) }
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
            val ev = validateEvent(body, id).copy(uid = old.uid, reminders = body.applyReminders(old.reminders))
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

    private fun validReminders(list: List<Int>, allDay: Boolean): List<Int> =
        try { Reminders.validate(list, allDay) } catch (e: IllegalArgumentException) { bad(e.message.orEmpty()) }

    /** Like the server's `EventData::validate`: returns the stored event (without UID, reminders validated). */
    private fun validateEvent(input: EventInput, id: String): CalEvent {
        input.applyReminders(null)?.let { validReminders(it, input.allDay) }
        val title = input.title.trim()
        if (title.isEmpty() || title.length > 500) bad("Der Titel muss 1–500 Zeichen lang sein")
        val tzName = input.timezone.trim().ifEmpty { "UTC" }
        val zone = ICal.zoneOrNull(tzName) ?: bad("Unbekannte Zeitzone: $tzName")
        val s = parse(input.start) ?: bad("Ungültiger Beginn: ${input.start}")
        val e = parse(input.end) ?: bad("Ungültiges Ende: ${input.end}")
        val (start, end) = if (input.allDay) {
            // All-day: midnight UTC, end exclusive
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

    /** Upper-case, remove `RRULE:` and convert UNTIL to UTC like the server. */
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
            // A plain date includes the whole day
            if (allDay) date.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(86_399)
            else date.atTime(23, 59, 59).atZone(zone).toInstant()
        }.recoverCatching {
            val local = LocalDateTime.parse(v, DATE_TIME)
            if (allDay) local.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(86_399)
            else local.atZone(zone).toInstant()
        }.getOrNull() ?: return v // rejected during validation
        return UNTIL_UTC.format(utc)
    }

    // ---------- Address books ----------

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

    /** Like `POST /addressbooks/{id}/import`: contacts with the same UID are updated. */
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
            // Cards without a UID get one derived from their content, so importing again doesn't create duplicates
            val uid = parsed.uid.ifEmpty { stableUid(raw.serialize()) }
            val base = parsed.toInput().copy(
                addressBookId = id,
                // Discard invalid values instead of the whole card
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

    // ---------- Contacts ----------

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

    /** Validates and stores a contact including its photo (`null` = unchanged, `""` = remove). */
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
            urls = input.urls, birthday = input.birthday, note = input.note,
            categories = input.categories ?: previous?.categories.orEmpty(), hasPhoto = photo != null,
        )
        // Photo first, so it is already there for the new ETag when the list shows it
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
        if (c.categories.orEmpty().let { l -> l.size > 100 || l.any { it.length > 100 } }) bad("Zu viele oder zu lange Kategorien")
        if (c.birthday.isNotEmpty() && Birthdays.parse(c.birthday) == null) bad("Ungültiger Geburtstag")
    }

    private fun validatePhoto(p: String) {
        if (p.length > MAX_PHOTO) bad("Das Foto ist zu groß (max. 3,5 MB)")
        if (!p.startsWith("data:") || ',' !in p) bad("Das Foto muss als data:-URL (Base64) übergeben werden")
    }

    private fun OfflineData.withBirthdays() = Birthdays.sync(this, ::newId, now())

    // ---------- Helpers ----------

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

        /** UID in UUID format from the content of a card */
        private fun stableUid(text: String): String {
            val h = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
            return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20, 32)}"
        }

        /** Like the server's `ContactData::normalize`: trim whitespace, omit empty entries, build the name. */
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
                categories = categories?.let(::cleanCategories),
                phones = phones.filter { it.value.isNotBlank() }.map { it.copy(type = known(it.type), value = it.value.trim()) },
                emails = emails.filter { it.value.isNotBlank() }.map { it.copy(type = known(it.type), value = it.value.trim()) },
                urls = urls.filter { it.value.isNotBlank() }.map { it.copy(type = known(it.type), value = it.value.trim()) },
                addresses = addresses.filter { a ->
                    listOf(a.poBox, a.extended, a.street, a.locality, a.region, a.postalCode, a.country).any { it.isNotBlank() }
                }.map { it.copy(type = known(it.type)) },
            )
            // A nickname takes precedence; otherwise, with name parts, always build it from them; otherwise keep an existing
            // name (as in the backend)
            val name = n.nickname.ifEmpty {
                listOf(n.prefix, n.givenName, n.additionalNames, n.familyName, n.suffix)
                    .filter { it.isNotEmpty() }.joinToString(" ")
            }
            if (name.isNotEmpty()) return n.copy(fullName = name)
            if (n.fullName.isNotEmpty()) return n
            val derived = (listOf(name, n.organization) + n.emails.map { it.value } + n.phones.map { it.value })
                .firstOrNull { it.isNotEmpty() }.orEmpty()
            return n.copy(fullName = derived)
        }
    }
}
