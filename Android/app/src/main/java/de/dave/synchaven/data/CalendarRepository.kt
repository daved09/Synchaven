package de.dave.synchaven.data

import de.dave.synchaven.data.api.SyncHavenApi
import de.dave.synchaven.data.api.apiCall
import de.dave.synchaven.data.model.CalEvent
import de.dave.synchaven.data.model.Calendar
import de.dave.synchaven.data.model.CalendarInput
import de.dave.synchaven.data.model.EventInput
import de.dave.synchaven.data.model.ExdateRequest
import de.dave.synchaven.data.model.ImportResult
import de.dave.synchaven.data.model.Occurrence
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Kalender und Termine. Die Kalenderliste wird zwischengespeichert, damit Kalenderansicht und
 * Termineditor dieselben Daten sehen; [eventsVersion] zählt jede Änderung an Terminen hoch,
 * sodass geöffnete Ansichten neu laden.
 */
class CalendarRepository(private val api: SyncHavenApi) {
    private val _calendars = MutableStateFlow<List<Calendar>?>(null)
    val calendars: StateFlow<List<Calendar>?> = _calendars.asStateFlow()

    private val _eventsVersion = MutableStateFlow(0L)
    val eventsVersion: StateFlow<Long> = _eventsVersion.asStateFlow()

    private fun eventsChanged() = _eventsVersion.update { it + 1 }

    suspend fun loadCalendars(): List<Calendar> =
        apiCall { api.calendars() }.also { _calendars.value = it }

    suspend fun ensureCalendars(): List<Calendar> = _calendars.value ?: loadCalendars()

    suspend fun createCalendar(input: CalendarInput): Calendar =
        apiCall { api.createCalendar(input) }.also { c -> _calendars.update { (it ?: emptyList()) + c } }

    suspend fun updateCalendar(id: String, input: CalendarInput): Calendar {
        val c = apiCall { api.updateCalendar(id, input) }
        _calendars.update { list -> list?.map { if (it.id == id) c else it } }
        return c
    }

    /** Blendet einen Kalender ein/aus – sofort lokal, danach auf dem Server (wie in der Weboberfläche). */
    suspend fun toggleVisible(cal: Calendar) {
        val toggled = cal.copy(visible = !cal.visible)
        _calendars.update { list -> list?.map { if (it.id == cal.id) toggled else it } }
        try {
            updateCalendar(cal.id, toggled.toInput())
        } catch (e: Exception) {
            _calendars.update { list -> list?.map { if (it.id == cal.id) cal else it } }
            throw e
        }
    }

    suspend fun deleteCalendar(id: String) {
        apiCall { api.deleteCalendar(id) }
        _calendars.update { list -> list?.filterNot { it.id == id } }
        eventsChanged()
    }

    suspend fun exportCalendar(id: String): String = apiCall { api.exportCalendar(id).string() }

    suspend fun importCalendar(id: String, ics: String, timezone: String): ImportResult =
        apiCall { api.importCalendar(id, timezone, ics.toRequestBody("text/calendar".toMediaType())) }
            .also { eventsChanged() }

    // ---------- Termine ----------

    suspend fun occurrences(start: Instant, end: Instant, query: String? = null): List<Occurrence> =
        apiCall { api.occurrences(start.toString(), end.toString(), query?.takeIf { it.isNotBlank() }) }

    suspend fun event(id: String): CalEvent = apiCall { api.event(id) }

    suspend fun createEvent(input: EventInput): CalEvent =
        apiCall { api.createEvent(input) }.also { eventsChanged() }

    suspend fun updateEvent(id: String, input: EventInput): CalEvent =
        apiCall { api.updateEvent(id, input) }.also { eventsChanged() }

    suspend fun deleteEvent(id: String) {
        apiCall { api.deleteEvent(id) }
        eventsChanged()
    }

    /** Entfernt ein einzelnes Vorkommen aus einer Serie */
    suspend fun removeOccurrence(eventId: String, occurrenceStart: String) {
        apiCall { api.addExdate(eventId, ExdateRequest(occurrenceStart)) }
        eventsChanged()
    }

    /**
     * Verschiebt ein Vorkommen auf neue Start-/Endzeit. Bei Serien wird nur dieses Vorkommen als eigener
     * Termin angelegt und in der Serie ausgenommen – erst anlegen, dann ausnehmen, damit bei einem Fehler
     * eher ein Duplikat als ein Verlust entsteht (wie in der Weboberfläche).
     */
    suspend fun moveOccurrence(occ: Occurrence, start: String, end: String) {
        try {
            val full = apiCall { api.event(occ.eventId) }
            val input = EventInput(
                calendarId = full.calendarId, title = full.title, description = full.description,
                location = full.location, start = start, end = end, allDay = full.allDay,
                timezone = full.timezone, rrule = full.rrule, exdates = full.exdates,
            )
            if (occ.recurring) {
                apiCall { api.createEvent(input.copy(rrule = null, exdates = emptyList())) }
                apiCall { api.addExdate(occ.eventId, ExdateRequest(occ.start)) }
            } else {
                apiCall { api.updateEvent(occ.eventId, input) }
            }
        } finally {
            eventsChanged()
        }
    }

    fun reset() {
        _calendars.value = null
        eventsChanged()
    }
}
