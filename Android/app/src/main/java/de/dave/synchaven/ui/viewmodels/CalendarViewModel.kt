package de.dave.synchaven.ui.viewmodels

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.dave.synchaven.R
import de.dave.synchaven.UiText
import de.dave.synchaven.data.AuthRepository
import de.dave.synchaven.data.CalendarRepository
import de.dave.synchaven.data.FileTransfer
import de.dave.synchaven.data.TaskRepository
import de.dave.synchaven.data.model.Calendar
import de.dave.synchaven.data.model.CalendarInput
import de.dave.synchaven.data.model.Occurrence
import de.dave.synchaven.data.model.Task
import de.dave.synchaven.data.model.TaskInput
import de.dave.synchaven.data.model.TaskList
import de.dave.synchaven.data.model.TaskListInput
import de.dave.synchaven.data.store.AppSettings
import de.dave.synchaven.data.store.SessionState
import de.dave.synchaven.data.store.SessionStore
import de.dave.synchaven.data.store.SettingsStore
import de.dave.synchaven.data.store.ViewMode
import de.dave.synchaven.data.userMessage
import de.dave.synchaven.pluralText
import de.dave.synchaven.ui.common.UiMessages
import de.dave.synchaven.uiText
import de.dave.synchaven.util.DateRange
import de.dave.synchaven.util.FMT_DATE_SHORT
import de.dave.synchaven.util.ViewEvent
import de.dave.synchaven.util.calendarTasks
import de.dave.synchaven.util.deviceZone
import de.dave.synchaven.util.localToAllDay
import de.dave.synchaven.util.startInstant
import de.dave.synchaven.util.stepDate
import de.dave.synchaven.util.toInstant
import de.dave.synchaven.util.toOffsetString
import de.dave.synchaven.util.toViewEvent
import de.dave.synchaven.util.viewRange
import de.dave.synchaven.util.viewTitle
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Ausgangspunkt des Blätterns: Von hier aus zählt der Pager seine Seiten. Er wechselt nur bei Sprüngen
 * (Heute, Datumswahl, Ansichtswechsel), nicht beim Wischen; [serial] macht jeden Sprung eindeutig,
 * [forward] gibt die Richtung der Übergangsanimation an.
 */
data class PageAnchor(val mode: ViewMode, val date: LocalDate, val serial: Int = 0, val forward: Boolean = true)

/** Angezeigter Zeitraum der Kalenderansicht */
data class CalendarPosition(val mode: ViewMode, val date: LocalDate, val anchor: PageAnchor = PageAnchor(mode, date))

data class CalendarUiState(
    val mode: ViewMode = ViewMode.MONTH,
    val date: LocalDate = LocalDate.now(),
    val range: DateRange = DateRange(date, date.plusDays(1)),
    val anchor: PageAnchor = PageAnchor(mode, date),
    val title: UiText = UiText.Plain(""),
    val settings: AppSettings = AppSettings(),
    val calendars: List<Calendar> = emptyList(),
    /** Termine der sichtbaren Kalender im Zeitraum samt vorigem und nächstem (für das Wischen) und fällige Aufgaben */
    val events: List<ViewEvent> = emptyList(),
    val taskLists: List<TaskList> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val loading: Boolean = false,
    val error: UiText? = null,
)

data class SearchState(
    val query: String = "",
    val active: Boolean = false,
    val loading: Boolean = false,
    val results: List<ViewEvent> = emptyList(),
)

enum class DeleteScope { OCCURRENCE, ALL }

@OptIn(FlowPreview::class)
class CalendarViewModel(
    private val repo: CalendarRepository,
    private val taskRepo: TaskRepository,
    settingsStore: SettingsStore,
    private val sessionStore: SessionStore,
    private val auth: AuthRepository,
    private val files: FileTransfer,
) : ViewModel() {
    val messages = UiMessages()

    private val position = MutableStateFlow(
        CalendarPosition(sessionStore.current?.defaultView ?: ViewMode.MONTH, LocalDate.now())
    )
    /** Ansicht wurde bereits bewusst gewählt – die Standardansicht aus dem Konto überschreibt sie nicht mehr */
    private var pinned = false
    private val raw = MutableStateFlow<List<Occurrence>>(emptyList())
    private val loading = MutableStateFlow(false)
    private val error = MutableStateFlow<UiText?>(null)

    private data class TaskData(val lists: List<TaskList>, val tasks: List<Task>)

    private val taskData = combine(taskRepo.lists, taskRepo.tasks) { lists, tasks -> TaskData(lists.orEmpty(), tasks.orEmpty()) }

    val state: StateFlow<CalendarUiState> = combine(
        position, settingsStore.settings, repo.calendars, raw, combine(loading, error, taskData, ::Triple),
    ) { pos, settings, calendars, occurrences, (isLoading, err, td) ->
        val range = viewRange(pos.mode, pos.date, settings.weekStart)
        val cals = calendars.orEmpty()
        val byId = cals.associateBy { it.id }
        val visible = cals.filter { it.visible }.map { it.id }.toSet()
        val events = occurrences.filter { it.calendarId in visible }.map { it.toViewEvent(byId) } +
            calendarTasks(td.lists, td.tasks)
        CalendarUiState(
            mode = pos.mode, date = pos.date, range = range, anchor = pos.anchor,
            title = viewTitle(pos.mode, pos.date, range),
            settings = settings, calendars = cals,
            // Aufgaben nach den Terminen desselben Zeitpunkts
            events = events.sortedWith(compareBy({ !it.allDay }, { it.start }, { it.isTask })),
            taskLists = td.lists, tasks = td.tasks,
            loading = isLoading, error = err,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, CalendarUiState())

    private val _search = MutableStateFlow(SearchState())
    val search = _search.asStateFlow()

    init {
        // Standardansicht könnte in der Weboberfläche geändert worden sein
        viewModelScope.launch { runCatching { auth.me() } }
        viewModelScope.launch {
            sessionStore.state.map { (it as? SessionState.LoggedIn)?.session?.defaultView }.distinctUntilChanged()
                .collect { mode -> if (mode != null && !pinned) jump(mode, position.value.date) }
        }
        viewModelScope.launch {
            runCatching { repo.loadCalendars() }.onFailure { error.value = it.userMessage() }
        }
        viewModelScope.launch { runCatching { taskRepo.load() } }
        // Termine für den sichtbaren Zeitraum und seine Nachbarn laden – neu bei Zeitraumwechsel und nach jeder Änderung
        viewModelScope.launch {
            combine(position, settingsStore.settings.map { it.weekStart }.distinctUntilChanged(), repo.eventsVersion) { pos, ws, _ ->
                loadWindow(pos.mode, pos.date, ws)
            }.collectLatest { range -> loadRange(range) }
        }
        viewModelScope.launch {
            _search.map { it.query.trim() to it.active }.distinctUntilChanged().debounce(400)
                .collectLatest { (q, active) -> if (active) runSearch(q) }
        }
    }

    /** Zeigt sofort die gespeicherten Termine und lädt dann vom Server (offline bleibt es beim Cache). */
    private suspend fun loadRange(range: DateRange) {
        val start = range.start.startInstant()
        val end = range.end.startInstant()
        val cached = repo.cachedOccurrences(start, end)
        if (cached != null) {
            raw.value = cached
            error.value = null
        }
        loading.value = true
        runCatching { repo.occurrences(start, end) }
            .onSuccess { raw.value = it; error.value = null }
            .onFailure { if (cached == null) error.value = it.userMessage() else messages.send(it.userMessage()) }
        loading.value = false
    }

    fun refresh() {
        repo.syncNow()
        viewModelScope.launch {
            runCatching { repo.loadCalendars() }.onFailure { messages.send(it.userMessage()) }
            runCatching { taskRepo.load() }.onFailure { messages.send(it.userMessage()) }
            val s = state.value
            loadRange(loadWindow(s.mode, s.date, s.settings.weekStart))
        }
    }

    // ---------- Navigation im Kalender ----------

    /** Vom Benutzer gewählte Ansicht – wird zugleich als Standardansicht im Benutzerkonto gespeichert */
    fun setMode(mode: ViewMode) {
        pinned = true
        jump(mode, position.value.date)
        if (sessionStore.current?.defaultView == mode) return
        viewModelScope.launch {
            runCatching { auth.setDefaultView(mode) }.onFailure { messages.send(it.userMessage()) }
        }
    }
    /** Der Pager ist durch Wischen auf dem Zeitraum um [date] zur Ruhe gekommen. */
    fun showPage(date: LocalDate) = position.update { it.copy(date = date) }
    fun today() = jump(position.value.mode, LocalDate.now())
    fun goto(date: LocalDate, mode: ViewMode? = null) {
        if (mode != null) pinned = true
        jump(mode ?: position.value.mode, date)
    }

    /** Springt zu [date] in [mode]; der Pager beginnt dort neu. */
    private fun jump(mode: ViewMode, date: LocalDate) = position.update {
        if (it.mode == mode && it.date == date) it
        else CalendarPosition(mode, date, PageAnchor(mode, date, it.anchor.serial + 1, forward = date >= it.date))
    }

    /** Zeitraum von der vorigen bis zur nächsten Seite, damit beim Wischen auch die Nachbarn Termine zeigen */
    private fun loadWindow(mode: ViewMode, date: LocalDate, weekStart: DayOfWeek) = DateRange(
        viewRange(mode, stepDate(mode, date, -1), weekStart).start,
        viewRange(mode, stepDate(mode, date, 1), weekStart).end,
    )

    // ---------- Suche ----------

    fun openSearch() = _search.update { it.copy(active = true) }
    fun closeSearch() {
        _search.value = SearchState()
    }
    fun onSearchChange(q: String) = _search.update { it.copy(query = q) }

    private suspend fun runSearch(q: String) {
        if (q.isEmpty()) {
            _search.update { it.copy(results = emptyList(), loading = false) }
            return
        }
        _search.update { it.copy(loading = true) }
        // Ein Jahr zurück und ein Jahr voraus (Server erlaubt bis zu 800 Tage)
        val today = LocalDate.now()
        runCatching { repo.occurrences(today.minusDays(365).startInstant(), today.plusDays(400).startInstant(), q) }
            .onSuccess { list ->
                val byId = state.value.calendars.associateBy { it.id }
                _search.update { s ->
                    s.copy(loading = false, results = list.map { it.toViewEvent(byId) }.sortedBy { it.start })
                }
            }
            .onFailure { e ->
                _search.update { it.copy(loading = false) }
                messages.send(e.userMessage())
            }
    }

    // ---------- Kalender ----------

    fun toggleCalendar(cal: Calendar) = launchAction { repo.toggleVisible(cal) }

    fun saveCalendar(id: String?, input: CalendarInput) = launchAction {
        if (id == null) repo.createCalendar(input) else repo.updateCalendar(id, input)
        messages.send(uiText(if (id == null) R.string.msg_calendar_created else R.string.msg_calendar_saved))
    }

    fun deleteCalendar(cal: Calendar) = launchAction {
        repo.deleteCalendar(cal.id)
        messages.send(uiText(R.string.msg_calendar_deleted, cal.name))
    }

    fun exportCalendar(cal: Calendar, uri: Uri) = launchAction {
        files.writeText(uri, repo.exportCalendar(cal.id))
        messages.send(uiText(R.string.msg_calendar_exported))
    }

    fun importCalendar(cal: Calendar, uri: Uri) = launchAction {
        val res = repo.importCalendar(cal.id, files.readText(uri), deviceZone().id)
        val imported = pluralText(R.plurals.msg_events_imported, res.imported)
        messages.send(
            if (res.skipped.isEmpty()) imported
            else pluralText(R.plurals.msg_import_skipped, res.skipped.size, imported, res.skipped.size)
        )
    }

    // ---------- Termine ----------

    fun deleteEvent(ev: ViewEvent, scope: DeleteScope) = launchAction {
        if (scope == DeleteScope.OCCURRENCE) repo.removeOccurrence(ev.occ.eventId, ev.occ.start)
        else repo.deleteEvent(ev.occ.eventId)
        messages.send(uiText(R.string.msg_event_deleted))
    }

    /**
     * Verschiebt einen Termin auf einen anderen Tag bzw. eine andere Startzeit, die Dauer bleibt erhalten.
     * Bei Serien wird nur dieses Vorkommen verschoben und aus der Serie gelöst.
     */
    fun moveEvent(ev: ViewEvent, newStart: LocalDateTime) = launchAction {
        val (start, end) = if (ev.allDay) {
            val days = ChronoUnit.DAYS.between(ev.start.toLocalDate(), newStart.toLocalDate())
            if (days == 0L) return@launchAction
            localToAllDay(ev.start.toLocalDate().plusDays(days)) to localToAllDay(ev.end.toLocalDate().plusDays(days))
        } else {
            val delta = Duration.between(ev.start, newStart)
            if (delta.isZero) return@launchAction
            newStart.toOffsetString() to ev.end.plus(delta).toOffsetString()
        }
        repo.moveOccurrence(ev.occ, start, end)
        messages.send(
            if (ev.occ.recurring) uiText(R.string.msg_event_moved_series, ev.start.format(FMT_DATE_SHORT))
            else uiText(R.string.msg_event_moved)
        )
    }

    // ---------- Aufgaben ----------

    fun toggleTaskList(list: TaskList) = launchAction { taskRepo.toggleVisible(list) }

    fun saveTaskList(id: String?, input: TaskListInput) = launchAction {
        if (id == null) taskRepo.createList(input) else taskRepo.updateList(id, input)
        messages.send(uiText(if (id == null) R.string.msg_task_list_created else R.string.msg_task_list_saved))
    }

    fun deleteTaskList(list: TaskList) = launchAction {
        taskRepo.deleteList(list.id)
        messages.send(uiText(R.string.msg_task_list_deleted, list.name))
    }

    fun saveTask(id: String?, input: TaskInput) = launchAction {
        if (id == null) taskRepo.createTask(input) else taskRepo.updateTask(id, input)
        messages.send(uiText(if (id == null) R.string.msg_task_created else R.string.msg_task_saved))
    }

    fun setTaskCompleted(task: Task, completed: Boolean) = launchAction {
        taskRepo.setCompleted(task, completed)
        messages.send(uiText(if (completed) R.string.msg_task_done else R.string.msg_task_reopened))
    }

    fun deleteTask(task: Task) = launchAction {
        taskRepo.deleteTask(task.id)
        messages.send(uiText(R.string.msg_task_deleted))
    }

    /** Verschiebt die Fälligkeit einer Aufgabe (Drag & Drop im Zeitraster); die Uhrzeit wandert mit. */
    fun moveTask(task: Task, newStart: LocalDateTime) = launchAction {
        val due = if (task.allDay) localToAllDay(newStart.toLocalDate()) else newStart.toInstant().toString()
        if (due == task.due) return@launchAction
        taskRepo.updateTask(task.id, task.toInput().copy(due = due))
        messages.send(uiText(R.string.msg_task_moved))
    }

    private fun launchAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onFailure { messages.send(it.userMessage()) }
        }
    }
}
