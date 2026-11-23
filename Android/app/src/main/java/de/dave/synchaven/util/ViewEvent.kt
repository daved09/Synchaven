package de.dave.synchaven.util

import de.dave.synchaven.data.model.Calendar
import de.dave.synchaven.data.model.Occurrence
import de.dave.synchaven.data.model.Task
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Vorkommen mit lokal berechneten Zeiten für die Darstellung – oder eine Aufgabe mit Fälligkeit ([task]) */
data class ViewEvent(
    val occ: Occurrence,
    val start: LocalDateTime,
    /** exklusives Ende */
    val end: LocalDateTime,
    val color: String,
    /** Aufgabe statt Termin: mit Abhak-Symbol, erledigt durchgestrichen */
    val task: Task? = null,
) {
    val isTask get() = task != null
    val completed get() = task?.completed == true

    val id get() = occ.id
    val title get() = occ.title
    val allDay get() = occ.allDay

    /** Ganztägig oder ≥ 24 h → wird als Balken statt im Zeitraster angezeigt */
    val isBar: Boolean get() = allDay || Duration.between(start, end).toHours() >= 24

    /** Letzter (inklusiver) Tag, an dem der Termin zu sehen ist */
    val lastDay: LocalDate
        get() = when {
            allDay -> end.toLocalDate().minusDays(1).coerceAtLeast(start.toLocalDate())
            end == start -> start.toLocalDate()
            else -> end.minusNanos(1).toLocalDate()
        }

    fun touches(day: LocalDate) = start.toLocalDate() <= day && lastDay >= day
}

fun Occurrence.toViewEvent(calendars: Map<String, Calendar>, zone: ZoneId = deviceZone()): ViewEvent {
    val s = if (allDay) allDayToLocal(start).atStartOfDay() else parseInstant(start).toLocal(zone)
    val e = if (allDay) allDayToLocal(end).atStartOfDay() else parseInstant(end).toLocal(zone)
    return ViewEvent(this, s, e, calendars[calendarId]?.color ?: "#6750A4")
}
