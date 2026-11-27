package de.dave.synchaven.data.local

import androidx.annotation.StringRes
import de.dave.synchaven.R
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
import de.dave.synchaven.data.model.MoveTaskRequest
import de.dave.synchaven.data.model.TASK_SORT_DUE
import de.dave.synchaven.data.model.TASK_SORT_MANUAL
import de.dave.synchaven.data.model.Task
import de.dave.synchaven.data.model.TaskInput
import de.dave.synchaven.data.model.TaskList
import de.dave.synchaven.data.model.TaskListInput
import de.dave.synchaven.data.model.UpdateMeRequest
import de.dave.synchaven.data.model.UpdateMeResponse
import de.dave.synchaven.data.model.User
import de.dave.synchaven.data.offline.OfflineData
import de.dave.synchaven.data.offline.OfflineStore
import de.dave.synchaven.data.offline.toLocalTask
import de.dave.synchaven.data.TaskOrder
import de.dave.synchaven.data.withTaskCompleted
import de.dave.synchaven.data.withTaskMoved
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

private const val DEFAULT_TASK_COLOR = "#1A73E8"

/**
 * "Server" of the local mode: the same interface as the REST API, but stored in the [OfflineStore] under
 * [LOCAL_ACCOUNT]. This way the repositories and the sync with Android work unchanged, even entirely without
 * the web UI. Validation, error messages and default names match those of the server (see backend/src/routes
 * and backend/locales) in the app's language, see [texts].
 */
class LocalApi(
    private val store: OfflineStore,
    /** Error messages and default names, like the server's for the same language */
    private val texts: LocalTexts,
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
                    Calendar(newId(), texts.get(R.string.api_calendar_default_name), "#6750A4", createdAt = ts, updatedAt = ts),
                ),
                events = d.events ?: emptyList(),
                addressBooks = d.addressBooks?.takeIf { it.isNotEmpty() } ?: listOf(AddressBook(newId(), texts.get(R.string.api_contacts_default_book_name))),
                contacts = d.contacts ?: emptyList(),
                tasks = d.tasks ?: emptyList(),
            ).withDefaultTaskList(ts).migrateLocal().let { Birthdays.sync(it, ::newId, ts, texts) }.recount()
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
    override suspend fun updateMe(body: UpdateMeRequest): UpdateMeResponse {
        if (body.name != null || body.email != null || body.newPassword != null) unavailable()
        return UpdateMeResponse(me().let { u -> body.defaultView?.let { u.copy(defaultView = it) } ?: u })
    }

    private fun localInfo() = ServerInfo(name = texts.get(R.string.local_account_name))

    // ---------- Tasks ----------

    /** Creates the default list ("My tasks", like the server) if there is none yet. */
    private fun OfflineData.withDefaultTaskList(ts: String): OfflineData =
        if (!taskLists.isNullOrEmpty()) this
        else copy(
            taskLists = listOf(
                TaskList(
                    newId(), texts.get(R.string.api_tasks_default_list_name), DEFAULT_TASK_COLOR, sortBy = TASK_SORT_MANUAL,
                    createdAt = ts, updatedAt = ts,
                ),
            ),
        )
    // Like backend/src/routes/tasks.rs: at least one list, the last one cannot be deleted.

    override suspend fun taskLists(): List<TaskList> {
        if (data().taskLists.isNullOrEmpty()) edit { it.withDefaultTaskList(now()) }
        return data().taskLists.orEmpty()
    }

    override suspend fun createTaskList(body: TaskListInput): TaskList {
        val (name, color) = validateTaskList(body)
        val ts = now()
        val list = TaskList(
            newId(), name, color ?: DEFAULT_TASK_COLOR, body.visible ?: true, sortBy = body.sortBy ?: TASK_SORT_MANUAL,
            createdAt = ts, updatedAt = ts,
        )
        edit { d -> d.copy(taskLists = d.taskLists.orEmpty() + list).recount() }
        return data().taskLists.orEmpty().first { it.id == list.id }
    }

    override suspend fun updateTaskList(id: String, body: TaskListInput): TaskList {
        val (name, color) = validateTaskList(body)
        edit { d ->
            val old = taskList(d, id)
            val list = old.copy(
                name = name, color = color ?: old.color, visible = body.visible ?: old.visible, sortBy = body.sortBy ?: old.sortBy,
                updatedAt = now(),
            )
            d.copy(taskLists = d.taskLists.orEmpty().map { if (it.id == id) list else it })
        }
        return taskList(data(), id)
    }

    override suspend fun deleteTaskList(id: String) {
        edit { d ->
            taskList(d, id)
            if (d.taskLists.orEmpty().size <= 1) bad(R.string.api_tasks_last_list)
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
            d.copy(tasks = d.tasks.orEmpty().withTaskSaved(t, place = true) { x, done -> x.withCompleted(done, ts) }).recount()
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
            d.copy(
                tasks = d.tasks.orEmpty().withTaskSaved(t, place = true) { x, done -> x.withCompleted(done, ts).copy(updatedAt = ts) },
            ).recount()
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

    /** Like `PUT /tasks/{id}/position`: [MoveTaskRequest.after] must be a task of the same level */
    override suspend fun moveTask(id: String, body: MoveTaskRequest): List<Task> {
        var level: List<Task> = emptyList()
        edit { d ->
            val t = task(d, id)
            val all = d.tasks.orEmpty()
            if (body.after != null && all.none { it.id == body.after && it.id != id && it.listId == t.listId && it.parentId == t.parentId }) {
                bad(R.string.api_tasks_not_sibling)
            }
            val ts = now()
            val moved = all.withTaskMoved(id, body.after).map { if (it.id == id) it.copy(updatedAt = ts) else it }
            level = moved.filter { it.listId == t.listId && it.parentId == t.parentId }.sortedWith(TaskOrder)
            d.copy(tasks = moved)
        }
        return level
    }

    private fun taskList(d: OfflineData, id: String) = d.taskLists.orEmpty().firstOrNull { it.id == id } ?: notFound()
    private fun task(d: OfflineData, id: String) = d.tasks.orEmpty().firstOrNull { it.id == id } ?: notFound()

    private fun validateTaskList(input: TaskListInput): Pair<String, String?> {
        val name = input.name.trim()
        if (name.isEmpty() || name.length > 100) bad(R.string.api_validation_name_length)
        if (input.sortBy != null && input.sortBy != TASK_SORT_MANUAL && input.sortBy != TASK_SORT_DUE) bad(R.string.api_tasks_sort_by)
        val color = input.color?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (!Regex("#[0-9A-Fa-f]{6}").matches(it)) bad(R.string.api_validation_color_format)
            it.uppercase()
        }
        return name to color
    }

    /** Like `resolve_parent` in the backend: one level deep, the parent task must exist. */
    private fun validateParent(d: OfflineData, existing: Task?, input: TaskInput) {
        val parentId = input.parentId ?: return
        if (existing?.id == parentId) bad(R.string.api_tasks_own_subtask)
        val parent = d.tasks.orEmpty().firstOrNull { it.id == parentId } ?: bad(R.string.api_tasks_parent_missing)
        if (parent.parentId != null) bad(R.string.api_tasks_nested_subtask)
        if (parent.listId == input.listId && existing != null && d.tasks.orEmpty().any { it.parentId == existing.id }) {
            bad(R.string.api_tasks_parent_with_subtasks)
        }
    }

    private fun validateTask(input: TaskInput) {
        val title = input.title.trim()
        if (title.isEmpty() || title.length > 500) bad(R.string.api_validation_title_length)
        if (input.due != null && parse(input.due) == null) bad(R.string.api_tasks_invalid_due)
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
            if (cal.isBirthdays) bad(R.string.api_calendar_birthdays_not_deletable)
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
            ICal.parse(ics.readText(), zone, untitled = texts.get(R.string.sync_untitled))
        } catch (_: IllegalArgumentException) {
            bad(R.string.api_ical_invalid_file)
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
                    validateEvent(input(e, null), "").also { skipped += texts.get(R.string.api_ical_rrule_ignored, e.title) }
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
        if (cal.isBirthdays) bad(R.string.api_calendar_birthdays_read_only)
    }

    private fun validateCalendar(input: CalendarInput): Pair<String, String?> {
        val name = input.name.trim()
        if (name.isEmpty() || name.length > 100) bad(R.string.api_validation_name_length)
        val color = input.color.trim().takeIf { it.isNotEmpty() }?.let {
            if (!Regex("#[0-9A-Fa-f]{6}").matches(it)) bad(R.string.api_validation_color_format)
            it.uppercase()
        }
        return name to color
    }

    // ---------- Events ----------

    override suspend fun occurrences(start: String, end: String, query: String?): List<Occurrence> {
        val from = parse(start) ?: bad(R.string.api_event_invalid_time, start)
        val to = parse(end) ?: bad(R.string.api_event_invalid_time, end)
        if (to <= from) bad(R.string.api_event_range_end_before_start)
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
            if (old.readOnly) bad(R.string.api_event_birthday_read_only)
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
            if (old.readOnly) bad(R.string.api_event_birthday_read_only)
            d.copy(events = d.events.orEmpty().filterNot { it.id == id })
        }
    }

    override suspend fun addExdate(id: String, body: ExdateRequest): CalEvent {
        val at = parse(body.occurrenceStart) ?: bad(R.string.api_event_invalid_time, body.occurrenceStart)
        var result: CalEvent? = null
        edit { d ->
            val old = d.events.orEmpty().firstOrNull { it.id == id } ?: notFound()
            if (old.readOnly) bad(R.string.api_event_birthday_read_only)
            if (old.rrule.isNullOrBlank()) bad(R.string.api_event_not_recurring)
            val ev = if (old.exdates.any { sameInstant(it, at) }) old
            else old.copy(exdates = (old.exdates + at.truncatedTo(ChronoUnit.SECONDS).toString()).sortedBy { parse(it) })
            result = ev
            d.copy(events = d.events.orEmpty().map { if (it.id == id) ev else it })
        }
        return result!!
    }

    private fun validReminders(list: List<Int>, allDay: Boolean): List<Int> =
        try {
            Reminders.validate(list, allDay)
        } catch (e: Reminders.InvalidRemindersException) {
            when {
                e.tooMany -> bad(R.string.api_reminders_too_many, Reminders.MAX_COUNT)
                allDay -> bad(R.string.api_reminders_range_all_day)
                else -> bad(R.string.api_reminders_range)
            }
        }

    /** Like the server's `EventData::validate`: returns the stored event (without UID, reminders validated). */
    private fun validateEvent(input: EventInput, id: String): CalEvent {
        input.applyReminders(null)?.let { validReminders(it, input.allDay) }
        val title = input.title.trim()
        if (title.isEmpty() || title.length > 500) bad(R.string.api_validation_title_length)
        val tzName = input.timezone.trim().ifEmpty { "UTC" }
        val zone = ICal.zoneOrNull(tzName) ?: bad(R.string.api_event_unknown_time_zone, tzName)
        val s = parse(input.start) ?: bad(R.string.api_event_invalid_start, input.start)
        val e = parse(input.end) ?: bad(R.string.api_event_invalid_end, input.end)
        val (start, end) = if (input.allDay) {
            // All-day: midnight UTC, end exclusive
            val ds = midnightUtc(s)
            val de = midnightUtc(e)
            ds to if (de <= ds) ds.plus(Duration.ofDays(1)) else de
        } else {
            if (e < s) bad(R.string.api_event_end_before_start)
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
        if (parts.isEmpty()) bad(R.string.api_recurrence_empty)
        val normalized = parts.joinToString(";")
        if (!RRuleExpander.isSupported(normalized)) bad(R.string.api_recurrence_invalid, normalized)
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
                addressBooks = rest.ifEmpty { listOf(AddressBook(newId(), texts.get(R.string.api_contacts_default_book_name))) },
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
        if (cards.isEmpty()) bad(R.string.api_contacts_no_vcards)
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
            val input = base.copy(fullName = base.fullName.ifEmpty { texts.get(R.string.contact_no_name) })
            if (uid.length > 500) {
                skipped += texts.get(R.string.api_contacts_uid_too_long, input.fullName)
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
        if (n.isEmpty() || n.length > 100) bad(R.string.api_validation_name_length)
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
        data().addressBooks.orEmpty().firstOrNull { it.id == body.addressBookId } ?: bad(R.string.api_contacts_book_not_found)
        return save(newId(), UUID.randomUUID().toString(), body, previous = null)
    }

    override suspend fun updateContact(id: String, body: ContactInput): Contact {
        val old = data().contacts.orEmpty().firstOrNull { it.id == id } ?: notFound()
        data().addressBooks.orEmpty().firstOrNull { it.id == body.addressBookId } ?: bad(R.string.api_contacts_book_not_found)
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
        if (c.fullName.isEmpty()) bad(R.string.api_contacts_name_required)
        if (c.fullName.length > 500 || c.note.length > 20_000) bad(R.string.api_contacts_too_long)
        if (c.categories.orEmpty().let { l -> l.size > 100 || l.any { it.length > 100 } }) bad(R.string.api_contacts_categories)
        if (c.birthday.isNotEmpty() && Birthdays.parse(c.birthday) == null) bad(R.string.api_contacts_invalid_birthday)
    }

    private fun validatePhoto(p: String) {
        if (p.length > MAX_PHOTO) bad(R.string.api_contacts_photo_too_large)
        if (!p.startsWith("data:") || ',' !in p) bad(R.string.api_contacts_photo_format)
    }

    private fun bad(@StringRes message: Int, vararg args: Any): Nothing =
        throw ApiException(400, "bad_request", texts.get(message, *args))
    private fun notFound(): Nothing = throw ApiException(404, "not_found", texts.get(R.string.api_error_not_found))
    private fun unavailable(): Nothing = throw ApiException(400, "local_mode", texts.get(R.string.api_local_unavailable))

    private fun OfflineData.withBirthdays() = Birthdays.sync(this, ::newId, now(), texts)

    // ---------- Helpers ----------

    private suspend fun RequestBody.readText(): String = Buffer().also { writeTo(it) }.readUtf8()

    companion object {
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
