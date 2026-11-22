package de.dave.synchaven.ui.calendar

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.dave.synchaven.R
import de.dave.synchaven.UiText
import de.dave.synchaven.data.AuthRepository
import de.dave.synchaven.data.CalendarRepository
import de.dave.synchaven.data.FileTransfer
import de.dave.synchaven.data.model.Calendar
import de.dave.synchaven.data.model.CalendarInput
import de.dave.synchaven.data.model.Occurrence
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
import de.dave.synchaven.util.deviceZone
import de.dave.synchaven.util.localToAllDay
import de.dave.synchaven.util.startInstant
import de.dave.synchaven.util.stepDate
import de.dave.synchaven.util.toOffsetString
import de.dave.synchaven.util.toViewEvent
import de.dave.synchaven.util.viewRange
import de.dave.synchaven.util.viewTitle
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

/** Angezeigter Zeitraum der Kalenderansicht */
data class CalendarPosition(val mode: ViewMode, val date: LocalDate)

data class CalendarUiState(
    val mode: ViewMode = ViewMode.MONTH,
    val date: LocalDate = LocalDate.now(),
    val range: DateRange = DateRange(date, date.plusDays(1)),
    val title: UiText = UiText.Plain(""),
    val settings: AppSettings = AppSettings(),
    val calendars: List<Calendar> = emptyList(),
    /** Termine der sichtbaren Kalender im Zeitraum */
    val events: List<ViewEvent> = emptyList(),
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

    val state: StateFlow<CalendarUiState> = combine(
        position, settingsStore.settings, repo.calendars, raw, combine(loading, error, ::Pair),
    ) { pos, settings, calendars, occurrences, (isLoading, err) ->
        val range = viewRange(pos.mode, pos.date, settings.weekStart)
        val cals = calendars.orEmpty()
        val byId = cals.associateBy { it.id }
        val visible = cals.filter { it.visible }.map { it.id }.toSet()
        CalendarUiState(
            mode = pos.mode, date = pos.date, range = range,
            title = viewTitle(pos.mode, pos.date, range),
            settings = settings, calendars = cals,
            events = occurrences.filter { it.calendarId in visible }.map { it.toViewEvent(byId) }
                .sortedWith(compareBy({ !it.allDay }, { it.start })),
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
                .collect { mode -> if (mode != null && !pinned) position.update { it.copy(mode = mode) } }
        }
        viewModelScope.launch {
            runCatching { repo.loadCalendars() }.onFailure { error.value = it.userMessage() }
        }
        // Termine für den sichtbaren Zeitraum laden – neu bei Zeitraumwechsel und nach jeder Änderung
        viewModelScope.launch {
            combine(position, settingsStore.settings.map { it.weekStart }.distinctUntilChanged(), repo.eventsVersion) { pos, ws, _ ->
                viewRange(pos.mode, pos.date, ws)
            }.collectLatest { range -> loadRange(range) }
        }
        viewModelScope.launch {
            _search.map { it.query.trim() to it.active }.distinctUntilChanged().debounce(400)
                .collectLatest { (q, active) -> if (active) runSearch(q) }
        }
    }

    private suspend fun loadRange(range: DateRange) {
        loading.value = true
        runCatching { repo.occurrences(range.start.startInstant(), range.end.startInstant()) }
            .onSuccess { raw.value = it; error.value = null }
            .onFailure { error.value = it.userMessage() }
        loading.value = false
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { repo.loadCalendars() }.onFailure { messages.send(it.userMessage()) }
            val s = state.value
            loadRange(s.range)
        }
    }

    // ---------- Navigation im Kalender ----------

    /** Vom Benutzer gewählte Ansicht – wird zugleich als Standardansicht im Benutzerkonto gespeichert */
    fun setMode(mode: ViewMode) {
        pinned = true
        position.update { it.copy(mode = mode) }
        if (sessionStore.current?.defaultView == mode) return
        viewModelScope.launch {
            runCatching { auth.setDefaultView(mode) }.onFailure { messages.send(it.userMessage()) }
        }
    }
    fun step(dir: Int) = position.update { it.copy(date = stepDate(it.mode, it.date, dir)) }
    fun today() = position.update { it.copy(date = LocalDate.now()) }
    fun goto(date: LocalDate, mode: ViewMode? = null) {
        if (mode != null) pinned = true
        position.update { CalendarPosition(mode ?: it.mode, date) }
    }

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

    private fun launchAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onFailure { messages.send(it.userMessage()) }
        }
    }
}
