package de.dave.synchaven.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import de.dave.synchaven.R
import de.dave.synchaven.UiText
import de.dave.synchaven.data.CalendarRepository
import de.dave.synchaven.data.model.Calendar
import de.dave.synchaven.data.model.EventInput
import de.dave.synchaven.data.store.AppSettings
import de.dave.synchaven.data.store.SettingsStore
import de.dave.synchaven.data.userMessage
import de.dave.synchaven.ui.navigation.EventEditRoute
import de.dave.synchaven.uiText
import de.dave.synchaven.util.Freq
import de.dave.synchaven.util.RecurrenceForm
import de.dave.synchaven.util.allDayToLocal
import de.dave.synchaven.util.buildRRule
import de.dave.synchaven.util.deviceZone
import de.dave.synchaven.util.emptyRecurrence
import de.dave.synchaven.util.localToAllDay
import de.dave.synchaven.util.parseInstant
import de.dave.synchaven.util.parseRRule
import de.dave.synchaven.util.toLocal
import de.dave.synchaven.util.toOffsetString
import de.dave.synchaven.util.remindersField
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EventEditUiState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val calendars: List<Calendar> = emptyList(),
    val calendarId: String = "",
    val title: String = "",
    val location: String = "",
    val description: String = "",
    val allDay: Boolean = false,
    val startDate: LocalDate = LocalDate.now(),
    val startTime: LocalTime = LocalTime.of(9, 0),
    /** Bei ganztägigen Terminen der letzte Tag (inklusive) */
    val endDate: LocalDate = LocalDate.now(),
    val endTime: LocalTime = LocalTime.of(10, 0),
    /** IANA-Zeitzone des Termins; neue Termine verwenden die Gerätezeitzone */
    val timezone: String = deviceZone().id,
    val recurrence: RecurrenceForm = RecurrenceForm(),
    val exdates: List<String> = emptyList(),
    /** Eigene Erinnerungen; `null` = Standard des Kalenders */
    val ownReminders: List<Int>? = null,
    val saving: Boolean = false,
    val error: UiText? = null,
    /** Termin wurde gespeichert → Bildschirm schließen */
    val done: Boolean = false,
) {
    val start: LocalDateTime get() = startDate.atTime(startTime)

    /** Angezeigte Erinnerungen: eigene oder die des gewählten Kalenders */
    val reminders: List<Int>
        get() = ownReminders ?: calendars.firstOrNull { it.id == calendarId }?.defaultReminders(allDay).orEmpty()
    val end: LocalDateTime get() = endDate.atTime(endTime)

    val validationError: UiText?
        get() = when {
            title.isBlank() -> uiText(R.string.error_title_required)
            title.length > 500 -> uiText(R.string.error_title_too_long)
            calendarId.isEmpty() -> uiText(R.string.error_calendar_required)
            allDay && endDate < startDate -> uiText(R.string.error_end_before_start)
            !allDay && end < start -> uiText(R.string.error_end_before_start)
            recurrence.freq == Freq.CUSTOM && recurrence.raw.isBlank() -> uiText(R.string.error_rrule_required)
            else -> null
        }
}

class EventEditViewModel(
    private val repo: CalendarRepository,
    settingsStore: SettingsStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    /** Für die Reihenfolge der Wochentage im Wiederholungseditor */
    val settings: StateFlow<AppSettings> = settingsStore.settings

    private val route = savedStateHandle.toRoute<EventEditRoute>()
    private val _state = MutableStateFlow(EventEditUiState(isNew = route.eventId == null))
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        runCatching {
            // In den Geburtstagskalender können keine Termine eingetragen werden.
            val calendars = repo.ensureCalendars().filterNot { it.isBirthdays }
            val defaultCal = calendars.firstOrNull { it.visible } ?: calendars.firstOrNull()
            val eventId = route.eventId
            if (eventId == null) {
                val start = route.startEpochSecond?.let { Instant.ofEpochSecond(it).toLocal() }
                    ?: LocalDateTime.now().plusHours(1).truncatedTo(ChronoUnit.HOURS)
                val end = if (route.allDay) start else start.plusHours(1)
                _state.update {
                    it.copy(
                        loading = false, calendars = calendars, calendarId = defaultCal?.id.orEmpty(),
                        allDay = route.allDay,
                        startDate = start.toLocalDate(), startTime = start.toLocalTime(),
                        endDate = end.toLocalDate(), endTime = end.toLocalTime(),
                        recurrence = emptyRecurrence(start.toLocalDate()),
                    )
                }
            } else {
                val ev = repo.event(eventId)
                val start: LocalDateTime
                val end: LocalDateTime
                if (ev.allDay) {
                    start = allDayToLocal(ev.start).atTime(9, 0)
                    // exklusives Ende → letzter Tag
                    end = allDayToLocal(ev.end).minusDays(1).coerceAtLeast(start.toLocalDate()).atTime(10, 0)
                } else {
                    start = parseInstant(ev.start).toLocal()
                    end = parseInstant(ev.end).toLocal()
                }
                _state.update {
                    it.copy(
                        loading = false, calendars = calendars, calendarId = ev.calendarId,
                        title = ev.title, location = ev.location, description = ev.description,
                        allDay = ev.allDay,
                        startDate = start.toLocalDate(), startTime = start.toLocalTime(),
                        endDate = end.toLocalDate(), endTime = end.toLocalTime(),
                        timezone = ev.timezone.ifBlank { deviceZone().id },
                        recurrence = parseRRule(ev.rrule, start.toLocalDate()),
                        exdates = ev.exdates,
                        ownReminders = ev.reminders,
                    )
                }
            }
        }.onFailure { e -> _state.update { it.copy(loading = false, error = e.userMessage()) } }
    }

    fun onTitleChange(v: String) = _state.update { it.copy(title = v, error = null) }
    fun onLocationChange(v: String) = _state.update { it.copy(location = v) }
    fun onDescriptionChange(v: String) = _state.update { it.copy(description = v) }
    fun onCalendarChange(id: String) = _state.update { it.copy(calendarId = id) }
    // Erinnerungen an ganztägige Termine bedeuten etwas anderes – beim Umschalten zurück zum Kalender-Standard
    fun onAllDayChange(v: Boolean) = _state.update { it.copy(allDay = v, ownReminders = null, error = null) }
    fun onRemindersChange(list: List<Int>) = _state.update { it.copy(ownReminders = list) }
    fun onUseDefaultReminders() = _state.update { it.copy(ownReminders = null) }
    fun onRecurrenceChange(form: RecurrenceForm) = _state.update { it.copy(recurrence = form, error = null) }

    /** Beginn ändern und das Ende so mitverschieben, dass die Dauer gleich bleibt. */
    fun onStartDateChange(date: LocalDate) = _state.update { s ->
        val days = ChronoUnit.DAYS.between(s.startDate, date)
        val recurrence = if (s.recurrence.freq == Freq.NONE) emptyRecurrence(date) else s.recurrence
        s.copy(startDate = date, endDate = s.endDate.plusDays(days), recurrence = recurrence, error = null)
    }

    fun onStartTimeChange(time: LocalTime) = _state.update { s ->
        val duration = Duration.between(s.start, s.end)
        val newEnd = s.startDate.atTime(time).plus(duration)
        s.copy(startTime = time, endDate = newEnd.toLocalDate(), endTime = newEnd.toLocalTime(), error = null)
    }

    fun onEndDateChange(date: LocalDate) = _state.update { it.copy(endDate = date, error = null) }
    fun onEndTimeChange(time: LocalTime) = _state.update { it.copy(endTime = time, error = null) }

    fun save() {
        val s = _state.value
        s.validationError?.let { msg -> _state.update { it.copy(error = msg) }; return }
        val input = EventInput(
            calendarId = s.calendarId,
            title = s.title.trim(),
            description = s.description,
            location = s.location.trim(),
            start = if (s.allDay) localToAllDay(s.startDate) else s.start.toOffsetString(),
            end = if (s.allDay) localToAllDay(s.endDate.plusDays(1)) else s.end.toOffsetString(),
            allDay = s.allDay,
            timezone = if (s.allDay) deviceZone().id else s.timezone,
            rrule = buildRRule(s.recurrence, s.startDate),
            exdates = s.exdates,
            reminders = remindersField(s.ownReminders),
        )
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            runCatching {
                val id = route.eventId
                if (id == null) repo.createEvent(input) else repo.updateEvent(id, input)
            }
                .onSuccess { _state.update { it.copy(saving = false, done = true) } }
                .onFailure { e -> _state.update { it.copy(saving = false, error = e.userMessage()) } }
        }
    }
}
