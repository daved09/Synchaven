package de.dave.synchaven.data

import de.dave.synchaven.data.api.SyncHavenApi
import de.dave.synchaven.util.Reminders
import de.dave.synchaven.util.remindersField
import de.dave.synchaven.data.api.apiCall
import de.dave.synchaven.data.model.CalEvent
import de.dave.synchaven.data.model.Calendar
import de.dave.synchaven.data.model.CalendarInput
import de.dave.synchaven.data.model.EventInput
import de.dave.synchaven.data.model.ExdateRequest
import de.dave.synchaven.data.model.ImportResult
import de.dave.synchaven.data.model.Occurrence
import de.dave.synchaven.data.offline.OfflineData
import de.dave.synchaven.data.offline.OfflineStore
import de.dave.synchaven.data.offline.OfflineSync
import de.dave.synchaven.data.offline.PendingOp
import de.dave.synchaven.data.offline.isLocalId
import de.dave.synchaven.data.offline.newLocalId
import de.dave.synchaven.data.offline.toLocalEvent
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Kalender und Termine. Kalender und alle Termine liegen im [OfflineStore], damit die App auch ohne Verbindung
 * zum Server funktioniert: Online werden die Vorkommen vom Server geholt, offline aus den gespeicherten Serien
 * berechnet. Änderungen ohne Verbindung landen in der Warteschlange von [OfflineSync].
 * [eventsVersion] zählt jede Änderung an Terminen hoch, sodass geöffnete Ansichten neu laden.
 */
class CalendarRepository(
    private val api: SyncHavenApi,
    private val store: OfflineStore,
    private val sync: OfflineSync,
    private val scope: CoroutineScope,
    /** Daten auf dem Server geändert – stößt die Synchronisierung mit dem Android-Kalender an */
    private val onServerChanged: () -> Unit = {},
) {
    val calendars: StateFlow<List<Calendar>?> =
        store.data.map { it.calendars }.stateIn(scope, SharingStarted.Eagerly, null)

    private val _eventsVersion = MutableStateFlow(0L)
    val eventsVersion: StateFlow<Long> = _eventsVersion.asStateFlow()

    /** Termine wurden auf dem Server geändert (auch indirekt, z. B. Geburtstage durch Kontaktänderungen). */
    fun eventsChanged() {
        localChange()
        onServerChanged()
    }

    /** Nur lokal geändert (offline) – Ansichten neu laden, die Synchronisierung folgt nach dem Übertragen. */
    private fun localChange() = _eventsVersion.update { it + 1 }

    /** Termine wurden außerhalb der App geändert (lokaler Modus: Android-Kalender) – nur die Ansichten neu laden. */
    fun refreshViews() = localChange()

    /** Kontakte wurden auf dem Server geändert: Geburtstagskalender neu laden. */
    fun birthdaysChanged() {
        scope.launch {
            runCatching {
                calendars.value.orEmpty().filter { it.isBirthdays }.forEach { refreshCalendarEvents(it.id) }
            }
            eventsChanged()
        }
    }

    /** Gleicht den Cache mit dem Server ab (ausstehende Änderungen übertragen, alles neu laden). */
    fun syncNow() = sync.requestSync()

    suspend fun loadCalendars(): List<Calendar> = sync.read(cached = { it.calendars }) {
        val list = apiCall { api.calendars() }
        sync.mergeServer { it.copy(calendars = list) }
        store.data.value.calendars ?: list
    }

    suspend fun ensureCalendars(): List<Calendar> {
        store.awaitReady()
        return store.data.value.calendars ?: loadCalendars()
    }

    suspend fun createCalendar(input: CalendarInput): Calendar {
        val c = sync.online { apiCall { api.createCalendar(input) } }
        sync.mergeServer { d -> d.copy(calendars = d.calendars.orEmpty().filterNot { it.id == c.id } + c) }
        onServerChanged()
        return c
    }

    suspend fun updateCalendar(id: String, input: CalendarInput): Calendar = sync.write(
        PendingOp.UpdateCalendar(id, input),
        online = {
            val c = apiCall { api.updateCalendar(id, input) }
            sync.mergeServer { d -> d.copy(calendars = d.calendars?.map { if (it.id == id) c else it }) }
            onServerChanged()
            c
        },
        offline = { d ->
            d.calendars?.firstOrNull { it.id == id }
                ?: Calendar(id, input.name, input.color, input.description, input.visible,
                    defaultReminders = input.defaultReminders ?: Reminders.DEFAULT_TIMED,
                    defaultAllDayReminders = input.defaultAllDayReminders ?: Reminders.DEFAULT_ALL_DAY)
        },
    )

    /** Blendet einen Kalender ein/aus – sofort lokal, danach auf dem Server (wie in der Weboberfläche). */
    suspend fun toggleVisible(cal: Calendar) {
        val toggled = cal.copy(visible = !cal.visible)
        sync.mergeServer { it.apply(PendingOp.UpdateCalendar(cal.id, toggled.toInput())) }
        try {
            updateCalendar(cal.id, toggled.toInput())
        } catch (e: Exception) {
            sync.mergeServer { it.apply(PendingOp.UpdateCalendar(cal.id, cal.toInput())) }
            throw e
        }
    }

    suspend fun deleteCalendar(id: String) {
        sync.online { apiCall { api.deleteCalendar(id) } }
        sync.mergeServer { d ->
            d.copy(calendars = d.calendars?.filterNot { it.id == id }, events = d.events?.filterNot { it.calendarId == id })
        }
        eventsChanged()
    }

    suspend fun exportCalendar(id: String): String = sync.online { apiCall { api.exportCalendar(id).string() } }

    suspend fun importCalendar(id: String, ics: String, timezone: String): ImportResult {
        val res = sync.online {
            apiCall { api.importCalendar(id, timezone, ics.toRequestBody("text/calendar".toMediaType())) }
        }
        runCatching { refreshCalendarEvents(id) }
        eventsChanged()
        return res
    }

    /** Lädt die Termine eines Kalenders neu in den Cache. */
    private suspend fun refreshCalendarEvents(calendarId: String) {
        val list = sync.online { apiCall { api.calendarEvents(calendarId) } }
        sync.mergeServer { d ->
            d.copy(events = d.events?.let { all -> all.filterNot { it.calendarId == calendarId } + list })
        }
    }

    // ---------- Termine ----------

    /**
     * Vorkommen im Zeitraum – vom Server, solange er erreichbar ist und keine Änderungen ausstehen, sonst aus dem
     * Cache berechnet.
     */
    suspend fun occurrences(start: Instant, end: Instant, query: String? = null): List<Occurrence> =
        withContext(Dispatchers.Default) {
            sync.read(preferCache = sync.hasAnyPending, cached = { it.localOccurrences(start, end, query) }) {
                apiCall { api.occurrences(start.toString(), end.toString(), query?.takeIf { it.isNotBlank() }) }
            }
        }

    /** Vorkommen aus dem Cache zum sofortigen Anzeigen; `null`, solange noch nichts gespeichert ist. */
    suspend fun cachedOccurrences(start: Instant, end: Instant): List<Occurrence>? {
        store.awaitReady()
        val data = store.data.value
        return withContext(Dispatchers.Default) { data.localOccurrences(start, end, null) }
    }

    private fun OfflineData.localOccurrences(start: Instant, end: Instant, query: String?) =
        if (calendars == null) null else occurrences(start, end, query)

    suspend fun event(id: String): CalEvent {
        store.awaitReady()
        val rid = store.data.value.resolve(id)
        return sync.read(
            preferCache = isLocalId(rid) || sync.hasPending(rid),
            cached = { d -> d.events?.firstOrNull { it.id == rid } },
        ) {
            apiCall { api.event(rid) }.also { patchEvent(it) }
        }
    }

    suspend fun createEvent(input: EventInput): CalEvent {
        val localId = newLocalId()
        return sync.write(
            PendingOp.CreateEvent(localId, input),
            online = { apiCall { api.createEvent(input) }.also { patchEvent(it); onServerChanged() } },
            offline = { d -> d.events?.firstOrNull { it.id == localId } ?: input.toLocalEvent(localId) },
        ).also { localChange() }
    }

    suspend fun updateEvent(id: String, input: EventInput): CalEvent = sync.write(
        PendingOp.UpdateEvent(id, input),
        online = { apiCall { api.updateEvent(id, input) }.also { patchEvent(it); onServerChanged() } },
        offline = { d -> d.events?.firstOrNull { it.id == d.resolve(id) } ?: input.toLocalEvent(d.resolve(id)) },
    ).also { localChange() }

    suspend fun deleteEvent(id: String) {
        sync.write(
            PendingOp.DeleteEvent(id),
            online = {
                apiCall { api.deleteEvent(id) }
                sync.mergeServer { d -> d.copy(events = d.events?.filterNot { it.id == id }) }
                onServerChanged()
            },
            offline = { },
        )
        localChange()
    }

    /** Entfernt ein einzelnes Vorkommen aus einer Serie */
    suspend fun removeOccurrence(eventId: String, occurrenceStart: String) {
        sync.write(
            PendingOp.AddExdate(eventId, occurrenceStart),
            online = { apiCall { api.addExdate(eventId, ExdateRequest(occurrenceStart)) }.also { patchEvent(it); onServerChanged() } },
            offline = { },
        )
        localChange()
    }

    /**
     * Verschiebt ein Vorkommen auf neue Start-/Endzeit. Bei Serien wird nur dieses Vorkommen als eigener
     * Termin angelegt und in der Serie ausgenommen – erst anlegen, dann ausnehmen, damit bei einem Fehler
     * eher ein Duplikat als ein Verlust entsteht (wie in der Weboberfläche).
     */
    suspend fun moveOccurrence(occ: Occurrence, start: String, end: String) {
        try {
            val full = event(occ.eventId)
            val input = EventInput(
                calendarId = full.calendarId, title = full.title, description = full.description,
                location = full.location, start = start, end = end, allDay = full.allDay,
                timezone = full.timezone, rrule = full.rrule, exdates = full.exdates,
                // Das herausgelöste Vorkommen behält die Erinnerungen der Serie
                reminders = remindersField(full.reminders),
            )
            if (occ.recurring) {
                createEvent(input.copy(rrule = null, exdates = emptyList()))
                removeOccurrence(occ.eventId, occ.start)
            } else {
                updateEvent(occ.eventId, input)
            }
        } finally {
            localChange()
        }
    }

    /** Übernimmt einen vom Server gelieferten Termin in den Cache. */
    private suspend fun patchEvent(ev: CalEvent) {
        sync.mergeServer { d ->
            d.copy(events = d.events?.let { list ->
                if (list.any { it.id == ev.id }) list.map { if (it.id == ev.id) ev else it } else list + ev
            })
        }
    }
}
